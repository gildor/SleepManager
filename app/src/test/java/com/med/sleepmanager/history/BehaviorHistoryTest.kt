package com.med.sleepmanager.history

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BehaviorHistoryTest {
    private val batteryContext = BehaviorContext(externalPowerConnected = false)

    private fun observation(
        sequence: Long,
        minutes: Long,
        state: BehaviorState = BehaviorState.STANDBY,
        event: BehaviorEvent = BehaviorEvent.STATE_CHANGE,
        percent: Int? = 90,
        run: String = "run-1",
        boot: Int? = 1,
        context: BehaviorContext = batteryContext
    ) = BehaviorObservation(
        sequence = sequence,
        runId = run,
        wallTimeMs = 1_000_000L + minutes * 60_000,
        elapsedTimeMs = minutes * 60_000,
        bootCount = boot,
        event = event,
        state = state,
        context = context,
        battery = BehaviorBattery(percent, status = BatteryChargeStatus.DISCHARGING)
    )

    @Test fun briefUsageHasItsOwnBatteryBoundariesBetweenSleepSessions() {
        val timeline = BehaviorTimeline.build(listOf(
            observation(1, 0, event = BehaviorEvent.START, percent = 92),
            observation(2, 400, BehaviorState.AWAKE, percent = 90),
            observation(3, 402, percent = 89),
            observation(4, 700, BehaviorState.AWAKE, percent = 88)
        ))
        assertEquals(listOf(BehaviorState.STANDBY, BehaviorState.AWAKE,
            BehaviorState.STANDBY, BehaviorState.AWAKE), timeline.map { it.state })
        val use = timeline[1]
        assertEquals(120_000L, use.durationMs)
        assertEquals(90, use.startBattery?.percent)
        assertEquals(89, use.endBattery?.percent)
        assertEquals(-1.0, use.batteryChangePercent!!, 0.00001)
        assertTrue(use.complete)
        assertFalse(timeline.first().complete)
        assertFalse(timeline.last().complete)
    }

    @Test fun protectedWakeAndResleepRemainInsideOneStandbySession() {
        val timeline = BehaviorTimeline.build(listOf(
            observation(1, 0, event = BehaviorEvent.START),
            observation(2, 10, event = BehaviorEvent.PROTECTED_WAKE),
            observation(3, 10, event = BehaviorEvent.RESLEEP),
            observation(4, 11, event = BehaviorEvent.PROTECTED_WAKE),
            observation(5, 60, BehaviorState.AWAKE)
        ))
        assertEquals(2, timeline.size)
        assertEquals(2, timeline.first().protectedWakeCount)
        assertEquals(60 * 60_000L, timeline.first().durationMs)
        assertEquals(0, timeline.last().protectedWakeCount)
    }

    @Test fun contextAndDuplicateStateDoNotCreateWakesOrSplitUsage() {
        val context = batteryContext.copy(lidClosed = true,
            externalDisplayConnected = true, externalDisplayActive = true)
        val timeline = BehaviorTimeline.build(listOf(
            observation(1, 0, event = BehaviorEvent.START),
            observation(2, 1, BehaviorState.AWAKE),
            observation(3, 2, BehaviorState.AWAKE, BehaviorEvent.CONTEXT, context = context),
            observation(4, 3, BehaviorState.AWAKE, context = context),
            observation(5, 20)
        ))
        assertEquals(3, timeline.size)
        assertEquals(19 * 60_000L, timeline[1].durationMs)
        assertEquals(0, timeline[1].protectedWakeCount)
        assertTrue(timeline[1].observations.any { it.context.externalDisplayActive == true })
    }

    @Test fun unavailableBatteryDoesNotRemoveAnObservedSession() {
        val a = observation(1, 0, BehaviorState.AWAKE, BehaviorEvent.START).copy(battery = null)
        val b = observation(2, 2).copy(battery = null)
        val session = BehaviorTimeline.build(listOf(a, b)).first()
        assertEquals(120_000L, session.durationMs)
        assertNull(session.batteryChangePercent)
        assertNull(session.drainMah)
    }

    private fun counter(percent: Int = 80, charge: Long, source: String = "RAW_COUNTER") =
        BehaviorBattery(percent, charge, 6_000_000, source, 6_000_000,
            BatteryChargeStatus.DISCHARGING)

    @Test fun subPercentCounterChangeIsPreservedWithoutInventingZeroConsumption() {
        val start = observation(1, 0, event = BehaviorEvent.START).copy(
            battery = counter(charge = 4_800_000))
        val finish = observation(2, 60, BehaviorState.AWAKE).copy(
            battery = counter(charge = 4_794_000))
        val session = BehaviorTimeline.build(listOf(start, finish)).first()
        assertEquals(-0.1, session.batteryChangePercent!!, 0.000001)
        assertEquals(6.0, session.drainMah!!, 0.000001)
        val unchanged = BehaviorTimeline.build(listOf(start,
            finish.copy(battery = start.battery))).first()
        assertEquals(0.0, unchanged.chargeChangeMah!!, 0.0)
        assertNull(unchanged.drainMah)
    }

    @Test fun incompatibleCounterSourcesOrScalesCannotCreateMeasuredMah() {
        val start = observation(1, 0, event = BehaviorEvent.START).copy(
            battery = counter(charge = 4_800_000))
        val finish = observation(2, 60, BehaviorState.AWAKE).copy(
            battery = counter(79, 4_000_000, "NORMALIZED_COUNTER"))
        val sourceChange = BehaviorTimeline.build(listOf(start, finish)).first()
        assertNull(sourceChange.chargeChangeMah)
        assertEquals(-1.0, sourceChange.batteryChangePercent!!, 0.0)
        val capacityChange = BehaviorTimeline.build(listOf(start,
            finish.copy(battery = start.battery!!.copy(capacityUah = 5_000_000)))).first()
        assertNull(capacityChange.chargeChangeMah)
        val normalizationChange = BehaviorTimeline.build(listOf(start,
            finish.copy(battery = start.battery.copy(normalizationFullUah = 9_000_000)))).first()
        assertNull(normalizationChange.chargeChangeMah)
        val unknownSource = start.battery.copy(source = "FUTURE_SOURCE")
        assertNull(BehaviorTimeline.build(listOf(start.copy(battery = unknownSource),
            finish.copy(battery = unknownSource))).first().chargeChangeMah)
    }

    @Test fun poweredAndUnknownPowerIntervalsShowNetChangeNotDischarge() {
        val start = observation(1, 0, BehaviorState.AWAKE, BehaviorEvent.START).copy(
            battery = counter(charge = 4_800_000))
        val powered = observation(2, 20, BehaviorState.AWAKE, BehaviorEvent.BATTERY,
            context = batteryContext.copy(externalPowerConnected = true)).copy(
            battery = counter(charge = 4_830_000).copy(status = BatteryChargeStatus.CHARGING))
        val finish = observation(3, 60).copy(battery = counter(charge = 4_860_000))
        val session = BehaviorTimeline.build(listOf(start, powered, finish)).first()
        assertEquals(60.0, session.chargeChangeMah!!, 0.0)
        assertFalse(session.onBatteryThroughout)
        assertNull(session.drainMah)
        val unknown = BehaviorTimeline.build(listOf(
            start.copy(context = BehaviorContext()), finish)).first()
        assertFalse(unknown.onBatteryThroughout)
    }

    @Test fun aRestartDoesNotContinueTheOldStateAcrossTheUnobservedGap() {
        val events = listOf(
            observation(1, 0, BehaviorState.AWAKE, BehaviorEvent.START),
            observation(2, 2, BehaviorState.AWAKE, BehaviorEvent.BATTERY),
            observation(3, 60, event = BehaviorEvent.START, run = "restarted"),
            observation(4, 70, BehaviorState.AWAKE, run = "restarted")
        )
        val sessions = BehaviorTimeline.build(events)
        assertEquals(120_000L, sessions.first().durationMs)
        assertEquals(BehaviorSessionEnd.LAST_OBSERVATION, sessions.first().end)
        assertEquals(10 * 60_000L, sessions[1].durationMs)
        assertFalse(sessions[1].startedByTransition)
    }

    @Test fun rebootClockAdjustmentsAndCorruptionBreakCoverage() {
        val start = observation(1, 0, event = BehaviorEvent.START)
        val lastKnown = observation(2, 2, event = BehaviorEvent.CONTEXT)
        val next = observation(3, 30)
        for (discontinuous in listOf(
            next.copy(bootCount = 2, elapsedTimeMs = 0),
            next.copy(wallTimeMs = next.wallTimeMs + 3_600_000),
            next.copy(wallTimeMs = 10_000),
            next.copy(elapsedTimeMs = 0),
            next.copy(event = BehaviorEvent.GAP)
        )) {
            val sessions = BehaviorTimeline.build(listOf(start, lastKnown, discontinuous))
            assertEquals(120_000L, sessions.first().durationMs)
            assertEquals(BehaviorSessionEnd.LAST_OBSERVATION, sessions.first().end)
            assertTrue(sessions.all { it.durationMs >= 0 })
        }
    }

    @Test fun stopEndsCoverageWithoutPretendingTheUsageSessionCompleted() {
        val session = BehaviorTimeline.build(listOf(
            observation(1, 0, BehaviorState.AWAKE, BehaviorEvent.START),
            observation(2, 5, BehaviorState.AWAKE, BehaviorEvent.STOP)
        ), "run-1", 10_000_000, 9_000_000).single()
        assertEquals(BehaviorSessionEnd.TRACKING_STOPPED, session.end)
        assertEquals(300_000L, session.durationMs)
        assertFalse(session.complete)
    }

    @Test fun onlyMatchingLiveRunCanExtendToNowWithoutAFabricatedBatteryReading() {
        val event = observation(1, 0, BehaviorState.AWAKE, BehaviorEvent.START)
        val wall = event.wallTimeMs + 120_000
        val elapsed = event.elapsedTimeMs + 120_000
        val live = BehaviorTimeline.build(listOf(event), "run-1", wall, elapsed).single()
        assertEquals(BehaviorSessionEnd.ONGOING, live.end)
        assertEquals(120_000L, live.durationMs)
        assertNull(live.endBattery)
        assertNull(live.batteryChangePercent)
        assertEquals(0L, BehaviorTimeline.build(listOf(event), "stale", wall, elapsed)
            .single().durationMs)
        assertEquals(BehaviorSessionEnd.LAST_OBSERVATION,
            BehaviorTimeline.build(listOf(event), "run-1", wall + 60_000, elapsed).single().end)
    }

    @Test fun appendSequencePreservesBoundaryOrderAcrossUnsortedReads() {
        val start = observation(1, 0, event = BehaviorEvent.START)
        val wake = observation(2, 1, BehaviorState.AWAKE)
        val sleep = observation(3, 3)
        val session = BehaviorTimeline.build(listOf(sleep, start, wake))[1]
        assertEquals(BehaviorState.AWAKE, session.state)
        assertEquals(120_000L, session.durationMs)
    }

    @Test fun retentionCoversSevenCalendarDatesAcrossBothDstChanges() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-10-29T04:00:00Z").toEpochMilli(),
            BehaviorHistoryRetention.cutoff(Instant.parse("2026-11-04T17:00:00Z").toEpochMilli(), zone))
        assertEquals(Instant.parse("2026-03-06T05:00:00Z").toEpochMilli(),
            BehaviorHistoryRetention.cutoff(Instant.parse("2026-03-12T16:00:00Z").toEpochMilli(), zone))
    }

    @Test fun versionedPayloadRoundTripsUnknownAndKnownContext() {
        for (context in listOf(BehaviorContext(),
            BehaviorContext(true, true, false, true))) {
            val event = observation(42, 5, BehaviorState.AWAKE, context = context).copy(
                battery = counter(charge = 4_800_000))
            assertEquals(event, BehaviorHistoryCodec.decode(BehaviorHistoryCodec.encode(event),
                event.sequence, event.runId, event.wallTimeMs, event.elapsedTimeMs))
        }
        val noBattery = observation(1, 0).copy(battery = null)
        assertEquals(noBattery, BehaviorHistoryCodec.decode(BehaviorHistoryCodec.encode(noBattery),
            1, noBattery.runId, noBattery.wallTimeMs, noBattery.elapsedTimeMs))
    }

    @Test fun invalidPayloadsDoNotBecomeValidMeasurements() {
        val valid = BehaviorHistoryCodec.encode(observation(1, 0))
        for (invalid in listOf("not json", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"percent\":90", "\"percent\":101"))) {
            assertTrue(runCatching {
                BehaviorHistoryCodec.decode(invalid, 1, "run", 1_000_000, 0)
            }.isFailure)
        }
    }
}
