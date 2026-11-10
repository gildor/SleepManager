package com.med.sleepmanager.history

import java.time.Instant
import java.time.ZoneId

enum class BehaviorState { AWAKE, STANDBY }

enum class BehaviorEvent {
    START, STATE_CHANGE, CONTEXT, BATTERY, PROTECTED_WAKE, RESLEEP, STOP, GAP
}

/** Observed context, not a guessed wake cause or a physical dock detector. */
data class BehaviorContext(
    val lidClosed: Boolean? = null,
    val externalDisplayConnected: Boolean? = null,
    val externalDisplayActive: Boolean? = null,
    val externalPowerConnected: Boolean? = null
)

enum class BatteryChargeStatus { UNKNOWN, CHARGING, DISCHARGING, FULL, NOT_CHARGING }

/** Preserve the counter's provenance; normalized and raw counters cannot be mixed. */
data class BehaviorBattery(
    val percent: Int?,
    val chargeUah: Long? = null,
    val capacityUah: Long? = null,
    val source: String = "UNAVAILABLE",
    val normalizationFullUah: Long? = null,
    val status: BatteryChargeStatus = BatteryChargeStatus.UNKNOWN
) {
    init {
        require(percent == null || percent in 0..100)
        require(chargeUah == null || chargeUah >= 0)
        require(capacityUah == null || capacityUah > 0)
    }

    internal fun compatibleWith(other: BehaviorBattery): Boolean =
        chargeUah != null && other.chargeUah != null &&
            source in setOf("RAW_COUNTER", "NORMALIZED_COUNTER", "PERCENT_DERIVED") &&
            source == other.source &&
            capacityUah == other.capacityUah &&
            normalizationFullUah == other.normalizationFullUah

    internal val precisePercent: Double?
        get() {
            val charge = chargeUah ?: return null
            val capacity = capacityUah ?: return null
            return (charge.toDouble() / capacity * 100.0)
                .takeIf { it.isFinite() && it in 0.0..100.0 }
        }
}

data class BehaviorObservation(
    val sequence: Long = 0,
    val runId: String,
    val wallTimeMs: Long,
    val elapsedTimeMs: Long,
    val bootCount: Int? = null,
    val event: BehaviorEvent,
    val state: BehaviorState,
    val context: BehaviorContext = BehaviorContext(),
    val battery: BehaviorBattery? = null
) {
    init {
        require(runId.isNotBlank())
        require(wallTimeMs > 0 && elapsedTimeMs >= 0)
    }
}

enum class BehaviorSessionEnd { STATE_CHANGE, TRACKING_STOPPED, LAST_OBSERVATION, ONGOING }

data class BehaviorSession(
    val state: BehaviorState,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val startedByTransition: Boolean,
    val end: BehaviorSessionEnd,
    val startBattery: BehaviorBattery?,
    val endBattery: BehaviorBattery?,
    val onBatteryThroughout: Boolean,
    val protectedWakeCount: Int,
    val observations: List<BehaviorObservation>
) {
    val complete: Boolean
        get() = startedByTransition && end == BehaviorSessionEnd.STATE_CHANGE

    /** Signed net battery change. External power does not measure device energy use. */
    val batteryChangePercent: Double?
        get() {
            val start = startBattery ?: return null
            val finish = endBattery ?: return null
            if (start.compatibleWith(finish)) {
                val a = start.precisePercent
                val b = finish.precisePercent
                if (a != null && b != null) return b - a
            }
            return if (start.percent != null && finish.percent != null) {
                (finish.percent - start.percent).toDouble()
            } else null
        }

    val chargeChangeMah: Double?
        get() {
            val start = startBattery ?: return null
            val finish = endBattery ?: return null
            if (!start.compatibleWith(finish)) return null
            return (finish.chargeUah!! - start.chargeUah!!).toDouble() / 1000.0
        }

    /** Unresolved/zero changes are not a precise zero-consumption claim. */
    val drainMah: Double?
        get() = if (onBatteryThroughout) {
            chargeChangeMah?.takeIf { it < 0 }?.let { -it }
        } else null
}

/**
 * Reconstructs whole tracked intervals, independent of Android and UI formatting.
 * A stopped/dead process never extends a persisted state through unobserved time.
 * Only a caller holding the live recorder's run ID may request an ongoing interval.
 */
object BehaviorTimeline {
    private const val CLOCK_TOLERANCE_MS = 2_000L

    fun build(
        observations: List<BehaviorObservation>,
        activeRunId: String? = null,
        nowWallTimeMs: Long? = null,
        nowElapsedTimeMs: Long? = null
    ): List<BehaviorSession> {
        val result = mutableListOf<BehaviorSession>()
        var first: BehaviorObservation? = null
        var last: BehaviorObservation? = null
        var startedByTransition = false
        var events = mutableListOf<BehaviorObservation>()

        fun finish(boundary: BehaviorObservation, end: BehaviorSessionEnd) {
            val start = first ?: return
            result += BehaviorSession(
                state = start.state,
                startedAt = start.wallTimeMs,
                endedAt = boundary.wallTimeMs,
                durationMs = (boundary.elapsedTimeMs - start.elapsedTimeMs).coerceAtLeast(0),
                startedByTransition = startedByTransition,
                end = end,
                startBattery = start.battery,
                endBattery = boundary.battery,
                onBatteryThroughout = (events + boundary).all {
                    it.context.externalPowerConnected == false &&
                        it.battery?.status == BatteryChargeStatus.DISCHARGING
                },
                protectedWakeCount = events.count { it.event == BehaviorEvent.PROTECTED_WAKE },
                observations = events.toList()
            )
        }

        // Sequence is append order. Wall time can change, so must not reorder the journal.
        observations.sortedBy { it.sequence }.forEach { observation ->
            val previous = last
            val discontinuity = previous != null && (
                previous.runId != observation.runId ||
                    previous.bootCount != observation.bootCount ||
                    !continuousClock(previous, observation)
                )
            if (discontinuity || observation.event == BehaviorEvent.GAP) {
                previous?.let { finish(it, BehaviorSessionEnd.LAST_OBSERVATION) }
                first = null
                last = null
                events = mutableListOf()
            }
            if (observation.event == BehaviorEvent.GAP) return@forEach
            if (observation.event == BehaviorEvent.STOP) {
                if (first != null) finish(observation, BehaviorSessionEnd.TRACKING_STOPPED)
                first = null
                last = null
                events = mutableListOf()
                return@forEach
            }
            val start = first
            if (start != null && start.state != observation.state) {
                finish(observation, BehaviorSessionEnd.STATE_CHANGE)
                first = observation
                events = mutableListOf()
                startedByTransition = observation.event == BehaviorEvent.STATE_CHANGE
            } else if (start == null) {
                first = observation
                events = mutableListOf()
                // Startup, pruning and recovery give a partial first session, not a wake.
                startedByTransition = false
            }
            events += observation
            last = observation
        }

        last?.let { previous ->
            val now = if (
                activeRunId == previous.runId && nowWallTimeMs != null &&
                nowElapsedTimeMs != null && nowWallTimeMs > 0 && nowElapsedTimeMs >= 0
            ) previous.copy(
                wallTimeMs = nowWallTimeMs,
                elapsedTimeMs = nowElapsedTimeMs,
                // An ongoing interval has no fabricated battery reading at "now".
                battery = null
            ).takeIf { continuousClock(previous, it) } else null
            if (now != null) {
                finish(now, BehaviorSessionEnd.ONGOING)
            } else {
                finish(previous, BehaviorSessionEnd.LAST_OBSERVATION)
            }
        }
        return result
    }

    private fun continuousClock(a: BehaviorObservation, b: BehaviorObservation): Boolean {
        val elapsed = b.elapsedTimeMs - a.elapsedTimeMs
        val wall = b.wallTimeMs - a.wallTimeMs
        return elapsed >= 0 && wall >= 0 &&
            kotlin.math.abs(wall - elapsed) <= CLOCK_TOLERANCE_MS
    }
}

object BehaviorHistoryRetention {
    const val MAX_OBSERVATIONS = 4096

    /** Today and the preceding six local dates, including 23/25-hour DST days. */
    fun cutoff(nowWallTimeMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowWallTimeMs).atZone(zone).toLocalDate()
            .minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
}
