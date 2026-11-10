package com.med.sleepmanager.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.med.sleepmanager.device.SysfsAccessCache
import com.med.sleepmanager.rules.BatteryCapacityPolicy
import com.med.sleepmanager.rules.BatteryCapacitySelection
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Persists lightweight battery measurements for screen-off sleep sessions.
 *
 * A session starts on a real screen-off and ends only on a real wake. Thor
 * closed-lid false wakes never call [finishSession], so they remain part of the
 * same sleep measurement.
 */
object BatterySleepStore {
    private const val PREFS = "battery_sleep_stats"

    private const val KEY_ACTIVE = "active"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_START_PERCENT = "start_percent"
    private const val KEY_START_CHARGE_UAH = "start_charge_uah"
    private const val KEY_START_CAPACITY_UAH = "start_capacity_uah"
    private const val KEY_START_CHARGING = "start_charging"
    private const val KEY_SAW_CHARGING = "saw_charging"
    private const val KEY_START_ELAPSED_MS = "start_elapsed_ms"
    private const val KEY_START_UPTIME_MS = "start_uptime_ms"
    private const val KEY_FALSE_WAKE_COUNT = "false_wake_count"
    private const val KEY_HISTORY = "history"

    private const val HISTORY_DAYS = 7L
    private const val MAX_HISTORY = 96
    const val MIN_AVERAGE_DURATION_MS = 3L * 60L * 60L * 1000L

    data class BatterySnapshot(
        val percent: Int?,
        val charging: Boolean,
        val externalPowerConnected: Boolean,
        val chargeCounterUah: Int?,
        val fullChargeUah: Long?,
        val designChargeUah: Long?
    ) {
        val capacitySelection: BatteryCapacitySelection
            get() =
                BatteryCapacityPolicy.select(
                    percent = percent,
                    chargeCounterUah = chargeCounterUah?.toLong(),
                    learnedFullUah = fullChargeUah,
                    designFullUah = designChargeUah
                )

        val chargeMah: Double?
            get() = capacitySelection.displayedCurrentUah?.div(1000.0)

        val rawBatteryHealthPercent: Double?
            get() =
                BatteryCapacityPolicy.rawBatteryHealthPercent(
                    learnedFullUah = fullChargeUah,
                    designFullUah = capacitySelection.effectiveDesignUah
                )

        val batteryHealthPercent: Double?
            get() = rawBatteryHealthPercent?.coerceAtMost(100.0)

        val precisePercent: Double?
            get() {
                val current =
                    capacitySelection.displayedCurrentUah ?: return null
                val full =
                    capacitySelection.selectedFullUah ?: return null
                if (current < 0 || full <= 0L) return null

                val value = current.toDouble() / full.toDouble() * 100.0
                return value.takeIf { it.isFinite() && it in 0.0..105.0 }
                    ?.coerceIn(0.0, 100.0)
            }
    }

    data class SleepSession(
        val startedAt: Long,
        val endedAt: Long,
        val startPercent: Int,
        val endPercent: Int,
        val drainPercent: Int,
        val durationMs: Long,
        val chargedDuringSleep: Boolean,
        val drainMah: Double?,
        val deepSleepMs: Long? = null,
        val preciseDrainPercent: Double? = null,
        val preciseBatteryChangePercent: Double? = null,
        val falseWakeCount: Int = 0,
        val startChargeUah: Long? = null,
        val endChargeUah: Long? = null
    ) {
        val drainPerHour: Double?
            get() {
                if (chargedDuringSleep || durationMs <= 0L) return null
                val measuredDrain =
                    preciseDrainPercent
                        ?.takeIf { it.isFinite() && it > 0.0 }
                        ?: drainPercent.toDouble().takeIf { it > 0.0 }
                        ?: return null
                return measuredDrain / (durationMs.toDouble() / 3_600_000.0)
            }

        val drainMahPerHour: Double?
            get() {
                val mah = drainMah ?: return null
                if (chargedDuringSleep || durationMs <= 0L) return null
                return mah / (durationMs.toDouble() / 3_600_000.0)
            }

        val deepSleepPercent: Double?
            get() {
                val deep = deepSleepMs ?: return null
                if (durationMs <= 0L) return null
                return (deep.toDouble() / durationMs.toDouble() * 100.0)
                    .coerceIn(0.0, 100.0)
            }
    }

    data class Dashboard(
        val currentPercent: Int?,
        val currentCharging: Boolean,
        val lastSession: SleepSession?,
        val averageDrainPerHour: Double?,
        val averageSessionCount: Int,
        val sessionActive: Boolean
    )

    data class Stats(
        val currentPercent: Int?,
        val currentPrecisePercent: Double?,
        val currentChargeMah: Double?,
        val estimatedCapacityMah: Double?,
        val batteryHealthPercent: Double?,
        val lastSession: SleepSession?,
        val averageDrainPerHour: Double?,
        val averageDrainMahPerHour: Double?,
        val averageDeepSleepPercent: Double?,
        val averageSessionCount: Int,
        val totalMeasuredSleepMs: Long,
        val bestDrainPerHour: Double?,
        val worstDrainPerHour: Double?,
        val estimatedHoursRemaining: Double?,
        val estimatedHoursFromFull: Double?
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentSnapshot(context: Context): BatterySnapshot {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        return snapshotFromIntent(context, batteryIntent)
    }

    internal fun snapshotFromIntent(
        context: Context,
        batteryIntent: Intent?,
        chargeCounterFallback: Int? = null
    ): BatterySnapshot {
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent =
            if (level >= 0 && scale > 0) {
                ((level * 100f) / scale).toInt().coerceIn(0, 100)
            } else {
                null
            }

        val status =
            batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged =
            batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0

        // ACTION_BATTERY_CHANGED carries the same charge-counter value used by
        // BatteryService. This also follows emulator battery overrides, whereas
        // BatteryManager.getIntProperty() may still expose the underlying HAL value.
        val broadcastCounter =
            batteryIntent?.getIntExtra("charge_counter", Int.MIN_VALUE)
                ?: Int.MIN_VALUE

        val batteryManager =
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val propertyCounter = chargeCounterFallback ?:
            runCatching {
                batteryManager?.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
                ) ?: Int.MIN_VALUE
            }.getOrDefault(Int.MIN_VALUE)

        val chargeCounter =
            listOf(broadcastCounter, propertyCounter)
                .firstOrNull { it != Int.MIN_VALUE && it >= 0 }

        return BatterySnapshot(
            percent = percent,
            charging = charging,
            externalPowerConnected = plugged != 0,
            chargeCounterUah = chargeCounter,
            fullChargeUah = SysfsAccessCache.readPositiveLong(
                "/sys/class/power_supply/battery/charge_full"
            ),
            designChargeUah = SysfsAccessCache.readPositiveLong(
                "/sys/class/power_supply/battery/charge_full_design"
            )
        )
    }

    fun beginSession(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_ACTIVE, false)) return

        val snapshot = currentSnapshot(context)
        val percent = snapshot.percent ?: return

        p.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_START_TIME, System.currentTimeMillis())
            .putInt(KEY_START_PERCENT, percent)
            .putInt(
                KEY_START_CHARGE_UAH,
                snapshot.capacitySelection.displayedCurrentUah
                    ?.takeIf { it <= Int.MAX_VALUE.toLong() }
                    ?.toInt()
                    ?: Int.MIN_VALUE
            )
            .putLong(
                KEY_START_CAPACITY_UAH,
                bestCapacityUah(snapshot) ?: -1L
            )
            .putBoolean(KEY_START_CHARGING, snapshot.charging)
            .putBoolean(KEY_SAW_CHARGING, snapshot.charging)
            .putLong(KEY_START_ELAPSED_MS, SystemClock.elapsedRealtime())
            .putLong(KEY_START_UPTIME_MS, SystemClock.uptimeMillis())
            .putInt(KEY_FALSE_WAKE_COUNT, 0)
            .commit()
    }

    fun noteCharging(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return
        p.edit().putBoolean(KEY_SAW_CHARGING, true).commit()
    }

    fun noteFalseWake(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return

        val current = p.getInt(KEY_FALSE_WAKE_COUNT, 0).coerceAtLeast(0)
        val next = if (current == Int.MAX_VALUE) current else current + 1
        p.edit().putInt(KEY_FALSE_WAKE_COUNT, next).commit()
    }

    fun finishSession(context: Context): SleepSession? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return null

        val startedAt = p.getLong(KEY_START_TIME, 0L)
        val startPercent = p.getInt(KEY_START_PERCENT, -1)
        val startChargeUah = p.getInt(KEY_START_CHARGE_UAH, Int.MIN_VALUE)
        val startCapacityUah = p.getLong(KEY_START_CAPACITY_UAH, -1L)
        val startCharging = p.getBoolean(KEY_START_CHARGING, false)
        val sawCharging = p.getBoolean(KEY_SAW_CHARGING, false)
        val startElapsedMs = p.getLong(KEY_START_ELAPSED_MS, -1L)
        val startUptimeMs = p.getLong(KEY_START_UPTIME_MS, -1L)
        val falseWakeCount =
            p.getInt(KEY_FALSE_WAKE_COUNT, 0).coerceAtLeast(0)

        val endElapsedMs = SystemClock.elapsedRealtime()
        val endUptimeMs = SystemClock.uptimeMillis()
        val end = currentSnapshot(context)
        val endPercent = end.percent

        clearActiveSession(p)

        if (startedAt <= 0L || startPercent !in 0..100 || endPercent == null) {
            return null
        }

        val endedAt = System.currentTimeMillis()
        val duration = max(0L, endedAt - startedAt)
        val chargedDuringSleep = startCharging || sawCharging || end.charging
        val drainPercent = max(0, startPercent - endPercent)

        val deepSleepMs =
            if (
                startElapsedMs >= 0L &&
                startUptimeMs >= 0L &&
                endElapsedMs >= startElapsedMs &&
                endUptimeMs >= startUptimeMs
            ) {
                val elapsedDelta = endElapsedMs - startElapsedMs
                val uptimeDelta = endUptimeMs - startUptimeMs
                (elapsedDelta - uptimeDelta).coerceIn(0L, duration)
            } else {
                null
            }

        val endCounter =
            end.capacitySelection.displayedCurrentUah
                ?.takeIf { it <= Int.MAX_VALUE.toLong() }
                ?.toInt()
        val drainMah =
            if (
                !chargedDuringSleep &&
                startChargeUah != Int.MIN_VALUE &&
                endCounter != null &&
                startChargeUah >= endCounter
            ) {
                (startChargeUah - endCounter) / 1000.0
            } else {
                null
            }

        val capacityUah =
            startCapacityUah.takeIf { it > 0L }
                ?: bestCapacityUah(end)
        val preciseBatteryChangePercent =
            if (
                startChargeUah != Int.MIN_VALUE &&
                endCounter != null &&
                capacityUah != null &&
                capacityUah > 0L
            ) {
                ((endCounter - startChargeUah).toDouble() /
                    capacityUah.toDouble() * 100.0)
                    .takeIf { it.isFinite() && it in -100.0..100.0 }
            } else {
                null
            }
        val preciseDrainPercent =
            if (!chargedDuringSleep) {
                preciseBatteryChangePercent
                    ?.let { -it }
                    ?.takeIf { it.isFinite() && it > 0.0 }
            } else {
                null
            }

        val session = SleepSession(
            startedAt = startedAt,
            endedAt = endedAt,
            startPercent = startPercent,
            endPercent = endPercent,
            drainPercent = drainPercent,
            durationMs = duration,
            chargedDuringSleep = chargedDuringSleep,
            drainMah = drainMah,
            deepSleepMs = deepSleepMs,
            preciseDrainPercent = preciseDrainPercent,
            preciseBatteryChangePercent = preciseBatteryChangePercent,
            falseWakeCount = falseWakeCount,
            startChargeUah =
                startChargeUah
                    .takeIf { it != Int.MIN_VALUE }
                    ?.toLong(),
            endChargeUah = endCounter?.toLong()
        )

        appendSession(context, session)
        return session
    }

    fun dashboard(context: Context): Dashboard {
        val current = currentSnapshot(context)
        val capacityMah = estimateCapacityMah(current)
        val sessions =
            readHistory(context).map {
                enrichHistoricalPrecision(it, capacityMah)
            }
        val eligible =
            sessions.filter(::isEligibleForLongTermStats)
        val measured =
            eligible.mapNotNull { session ->
                effectiveDrainPercent(session)?.let { session to it }
            }

        val totalDurationHours =
            measured.sumOf { it.first.durationMs }.toDouble() / 3_600_000.0
        val totalDrain = measured.sumOf { it.second }
        val average =
            if (measured.isNotEmpty() && totalDurationHours > 0.0) {
                totalDrain / totalDurationHours
            } else {
                null
            }

        return Dashboard(
            currentPercent = current.percent,
            currentCharging = current.charging,
            lastSession = sessions.maxByOrNull { it.endedAt },
            averageDrainPerHour = average,
            averageSessionCount = measured.size,
            sessionActive = prefs(context).getBoolean(KEY_ACTIVE, false)
        )
    }

    fun stats(context: Context): Stats {
        val current = currentSnapshot(context)
        val capacityMah = estimateCapacityMah(current)
        val sessions =
            readHistory(context).map {
                enrichHistoricalPrecision(it, capacityMah)
            }
        val eligible =
            sessions.filter(::isEligibleForLongTermStats)
        val measured =
            eligible.mapNotNull { session ->
                effectiveDrainPercent(session)?.let { session to it }
            }

        val totalDurationHours =
            measured.sumOf { it.first.durationMs }.toDouble() / 3_600_000.0
        val averageDrainPerHour =
            if (measured.isNotEmpty() && totalDurationHours > 0.0) {
                measured.sumOf { it.second } / totalDurationHours
            } else {
                null
            }

        val mahSessions =
            eligible.filter {
                it.drainMah?.let { mah -> mah.isFinite() && mah > 0.0 } == true
            }
        val mahDurationHours =
            mahSessions.sumOf { it.durationMs }.toDouble() / 3_600_000.0
        val averageDrainMahPerHour =
            if (mahSessions.isNotEmpty() && mahDurationHours > 0.0) {
                mahSessions.sumOf { it.drainMah ?: 0.0 } / mahDurationHours
            } else {
                null
            }

        val deepSessions =
            eligible.mapNotNull { session ->
                session.deepSleepPercent?.let { percent ->
                    percent to session.durationMs
                }
            }
        val deepDuration = deepSessions.sumOf { it.second }.toDouble()
        val averageDeepSleepPercent =
            if (deepSessions.isNotEmpty() && deepDuration > 0.0) {
                deepSessions.sumOf { pair ->
                    pair.first * pair.second.toDouble()
                } / deepDuration
            } else {
                null
            }

        val drainRates = measured.mapNotNull { it.first.drainPerHour }
        val percent = current.percent
        val estimatedHoursRemaining =
            if (
                averageDrainPerHour != null &&
                averageDrainPerHour > 0.0 &&
                percent != null
            ) {
                percent / averageDrainPerHour
            } else {
                null
            }
        val estimatedHoursFromFull =
            if (averageDrainPerHour != null && averageDrainPerHour > 0.0) {
                100.0 / averageDrainPerHour
            } else {
                null
            }

        return Stats(
            currentPercent = percent,
            currentPrecisePercent = current.precisePercent,
            currentChargeMah = current.chargeMah,
            estimatedCapacityMah = estimateCapacityMah(current),
            batteryHealthPercent = current.batteryHealthPercent,
            lastSession = sessions.maxByOrNull { it.endedAt },
            averageDrainPerHour = averageDrainPerHour,
            averageDrainMahPerHour = averageDrainMahPerHour,
            averageDeepSleepPercent = averageDeepSleepPercent,
            averageSessionCount = eligible.size,
            totalMeasuredSleepMs = eligible.sumOf { it.durationMs },
            bestDrainPerHour = drainRates.minOrNull(),
            worstDrainPerHour = drainRates.maxOrNull(),
            estimatedHoursRemaining = estimatedHoursRemaining,
            estimatedHoursFromFull = estimatedHoursFromFull
        )
    }

    fun clearHistory(context: Context) {
        prefs(context).edit()
            .remove(KEY_HISTORY)
            .commit()
    }

    private fun appendSession(context: Context, session: SleepSession) {
        val now = System.currentTimeMillis()
        val cutoff = now - HISTORY_DAYS * 24L * 60L * 60L * 1000L
        val sessions =
            (readHistory(context) + session)
                .filter { it.endedAt >= cutoff }
                .sortedByDescending { it.endedAt }
                .take(MAX_HISTORY)

        val array = JSONArray()
        sessions.forEach { item ->
            array.put(
                JSONObject()
                    .put("startedAt", item.startedAt)
                    .put("endedAt", item.endedAt)
                    .put("startPercent", item.startPercent)
                    .put("endPercent", item.endPercent)
                    .put("drainPercent", item.drainPercent)
                    .put("durationMs", item.durationMs)
                    .put("chargedDuringSleep", item.chargedDuringSleep)
                    .apply {
                        item.drainMah?.let { put("drainMah", it) }
                        item.deepSleepMs?.let { put("deepSleepMs", it) }
                        item.preciseDrainPercent?.let {
                            put("preciseDrainPercent", it)
                        }
                        item.preciseBatteryChangePercent?.let {
                            put("preciseBatteryChangePercent", it)
                        }
                        item.startChargeUah?.let {
                            put("startChargeUah", it)
                        }
                        item.endChargeUah?.let {
                            put("endChargeUah", it)
                        }
                        put("falseWakeCount", item.falseWakeCount.coerceAtLeast(0))
                    }
            )
        }

        prefs(context).edit()
            .putString(KEY_HISTORY, array.toString())
            .commit()
    }

    private fun readHistory(context: Context): List<SleepSession> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        val now = System.currentTimeMillis()
        val cutoff = now - HISTORY_DAYS * 24L * 60L * 60L * 1000L

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val endedAt = item.optLong("endedAt", 0L)
                    if (endedAt < cutoff) continue

                    add(
                        SleepSession(
                            startedAt = item.optLong("startedAt", 0L),
                            endedAt = endedAt,
                            startPercent = item.optInt("startPercent", -1),
                            endPercent = item.optInt("endPercent", -1),
                            drainPercent = item.optInt("drainPercent", 0),
                            durationMs = item.optLong("durationMs", 0L),
                            chargedDuringSleep =
                                item.optBoolean("chargedDuringSleep", false),
                            drainMah =
                                if (item.has("drainMah")) {
                                    item.optDouble("drainMah")
                                } else {
                                    null
                                },
                            deepSleepMs =
                                if (item.has("deepSleepMs")) {
                                    item.optLong("deepSleepMs")
                                } else {
                                    null
                                },
                            preciseDrainPercent =
                                if (item.has("preciseDrainPercent")) {
                                    item.optDouble("preciseDrainPercent")
                                        .takeIf { it.isFinite() && it > 0.0 }
                                } else {
                                    null
                                },
                            preciseBatteryChangePercent =
                                if (item.has("preciseBatteryChangePercent")) {
                                    item.optDouble("preciseBatteryChangePercent")
                                        .takeIf { it.isFinite() }
                                } else {
                                    null
                                },
                            falseWakeCount =
                                item.optInt("falseWakeCount", 0)
                                    .coerceAtLeast(0),
                            startChargeUah =
                                if (item.has("startChargeUah")) {
                                    item.optLong("startChargeUah")
                                        .takeIf { it >= 0L }
                                } else {
                                    null
                                },
                            endChargeUah =
                                if (item.has("endChargeUah")) {
                                    item.optLong("endChargeUah")
                                        .takeIf { it >= 0L }
                                } else {
                                    null
                                }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun clearActiveSession(
        prefs: android.content.SharedPreferences
    ) {
        prefs.edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_START_TIME)
            .remove(KEY_START_PERCENT)
            .remove(KEY_START_CHARGE_UAH)
            .remove(KEY_START_CAPACITY_UAH)
            .remove(KEY_START_CHARGING)
            .remove(KEY_SAW_CHARGING)
            .remove(KEY_START_ELAPSED_MS)
            .remove(KEY_START_UPTIME_MS)
            .remove(KEY_FALSE_WAKE_COUNT)
            .commit()
    }

    internal fun effectiveDrainPercent(session: SleepSession): Double? =
        if (session.chargedDuringSleep) {
            null
        } else {
            session.preciseDrainPercent
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: session.drainPercent.toDouble().takeIf { it > 0.0 }
        }

    internal fun deriveDrainPercentFromMah(
        drainMah: Double?,
        capacityMah: Double?
    ): Double? {
        val drain = drainMah?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val capacity =
            capacityMah?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return (drain / capacity * 100.0)
            .takeIf { it.isFinite() && it > 0.0 && it <= 100.0 }
    }

    internal fun sessionAnalyticsEligible(session: SleepSession): Boolean =
        !session.chargedDuringSleep &&
            session.durationMs >= MIN_AVERAGE_DURATION_MS

    internal fun drainSampleExclusionReason(
        session: SleepSession
    ): String? =
        when {
            session.chargedDuringSleep ->
                "charged during sleep"
            session.durationMs < MIN_AVERAGE_DURATION_MS ->
                "sleep shorter than 3 hours"
            effectiveDrainPercent(session) == null ->
                "no measurable battery decrease"
            else ->
                null
        }

    private fun isEligibleForLongTermStats(session: SleepSession): Boolean =
        sessionAnalyticsEligible(session)

    private fun enrichHistoricalPrecision(
        session: SleepSession,
        capacityMah: Double?
    ): SleepSession {
        if (
            session.preciseDrainPercent != null ||
            session.chargedDuringSleep
        ) {
            return session
        }

        val derived =
            deriveDrainPercentFromMah(
                drainMah = session.drainMah,
                capacityMah = capacityMah
            ) ?: return session

        return session.copy(
            preciseDrainPercent = derived,
            preciseBatteryChangePercent =
                session.preciseBatteryChangePercent ?: -derived
        )
    }

    private fun bestCapacityUah(snapshot: BatterySnapshot): Long? =
        snapshot.capacitySelection.selectedFullUah

    private fun estimateCapacityMah(snapshot: BatterySnapshot): Double? =
        bestCapacityUah(snapshot)?.div(1000.0)

}
