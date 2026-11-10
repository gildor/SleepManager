package com.med.sleepmanager.history

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.med.sleepmanager.data.AppPreferences
import com.med.sleepmanager.data.BatterySleepStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BehaviorHistoryStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "history-test-${UUID.randomUUID()}.db"
    private lateinit var database: BehaviorHistoryDatabase
    private var liveRecorder: BehaviorHistoryRecorder? = null
    private val now = Instant.parse("2026-11-04T17:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("America/New_York")

    @Before fun open() {
        database = BehaviorHistoryDatabase(context, name)
    }

    @After fun close() {
        val completion = AtomicReference<Future<*>?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            completion.set(liveRecorder?.stop())
            liveRecorder = null
        }
        completion.get()?.get(10, TimeUnit.SECONDS)
        database.close()
        context.deleteDatabase(name)
    }

    private fun event(time: Long, kind: BehaviorEvent = BehaviorEvent.CONTEXT) =
        BehaviorObservation(
            runId = "test-run", wallTimeMs = time, elapsedTimeMs = time / 2,
            event = kind, state = BehaviorState.STANDBY
        )

    @Test fun historySurvivesReopeningWithoutReorderingAndPreservesUnknownContext() {
        val a = event(now)
        val b = event(now - 60_000) // A backwards clock must not reorder append history.
        database.append(a, now, zone)
        database.append(b, now, zone)
        database.close()
        database = BehaviorHistoryDatabase(context, name)
        val read = database.read()
        assertEquals(listOf(a.wallTimeMs, b.wallTimeMs), read.map { it.wallTimeMs })
        assertTrue(read[0].sequence < read[1].sequence)
        assertNull(read[0].context.lidClosed)
        assertNull(read[0].battery)
    }

    @Test fun retentionKeepsOneBoundaryBeforeSevenLocalDates() {
        val cutoff = BehaviorHistoryRetention.cutoff(now, zone)
        for (time in listOf(cutoff - 120_000, cutoff - 60_000, cutoff, now)) {
            database.append(event(time), now, zone)
        }
        assertEquals(listOf(cutoff - 60_000, cutoff, now), database.read().map { it.wallTimeMs })
    }

    @Test fun hardLimitBoundsEvenABurstOfRecentEvents() {
        for (i in 0..BehaviorHistoryRetention.MAX_OBSERVATIONS + 1) {
            database.append(event(now + i), now, zone)
        }
        val read = database.read()
        assertEquals(BehaviorHistoryRetention.MAX_OBSERVATIONS, read.size)
        assertEquals(now + 2, read.first().wallTimeMs)
        assertEquals(now + BehaviorHistoryRetention.MAX_OBSERVATIONS + 1, read.last().wallTimeMs)
    }

    @Test fun badPayloadIsACoverageGapNotASilentlySkippedTransition() {
        database.append(event(now, BehaviorEvent.START), now, zone)
        database.append(event(now + 1000), now, zone)
        database.append(event(now + 2000), now, zone)
        database.writableDatabase.execSQL(
            "UPDATE observations SET payload = 'corrupt' WHERE id = ?",
            arrayOf(database.read()[1].sequence)
        )
        val read = database.read()
        assertEquals(BehaviorEvent.GAP, read[1].event)
        val sessions = BehaviorTimeline.build(read)
        assertEquals(2, sessions.size)
        assertEquals(0L, sessions.first().durationMs)
        assertFalse(sessions.last().startedByTransition)
    }

    @Test fun recorderPersistsProtectedWakeAndRealUsageWithoutAdvancedDiagnostics() {
        val advanced = AppPreferences.advancedDiagnosticsEnabled(context)
        AppPreferences.setAdvancedDiagnosticsEnabled(context, false)
        val completion = AtomicReference<Future<*>?>()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val recorder = BehaviorHistoryRecorder(context, initialLidClosed = true,
                    persist = { database.append(it) })
                liveRecorder = recorder
                recorder.start(BehaviorState.STANDBY)
                recorder.wake(suppressed = true)
                recorder.protectedResleep()
                recorder.lid(false)
                recorder.wake(suppressed = false)
                recorder.standby()
                completion.set(recorder.stop())
            }
            completion.get()!!.get(10, TimeUnit.SECONDS)
            val read = database.read()
            assertTrue(read.any { it.event == BehaviorEvent.PROTECTED_WAKE })
            assertEquals(BehaviorEvent.STOP, read.last().event)
            assertNull(BehaviorHistoryRecorder.activeRunId)
            val sessions = BehaviorTimeline.build(read)
            assertEquals(listOf(BehaviorState.STANDBY, BehaviorState.AWAKE, BehaviorState.STANDBY),
                sessions.map { it.state })
            assertEquals(1, sessions.first().protectedWakeCount)
            assertTrue(sessions[1].complete)
        } finally {
            AppPreferences.setAdvancedDiagnosticsEnabled(context, advanced)
        }
    }

    @Test fun failedWriteStartsNewCoverageInsteadOfBridgingALostWake() {
        var writes = 0
        val completion = AtomicReference<Future<*>?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val recorder = BehaviorHistoryRecorder(context, persist = {
                writes++
                if (writes == 2) throw java.io.IOException("Injected lost wake")
                database.append(it)
            })
            liveRecorder = recorder
            recorder.start(BehaviorState.STANDBY)
            recorder.wake(suppressed = false)
            recorder.standby()
            completion.set(recorder.stop())
        }
        completion.get()!!.get(10, TimeUnit.SECONDS)
        val read = database.read()
        assertEquals(2, read.map { it.runId }.distinct().size)
        val sessions = BehaviorTimeline.build(read)
        assertTrue(sessions.all { it.state == BehaviorState.STANDBY })
        assertTrue(sessions.none { it.complete })
    }

    @Test fun aCapturedCounterNeverFallsBackToALaterHardwareReading() {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 80)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra("charge_counter", 4_800_000)
        val captured = BatterySleepStore.snapshotFromIntent(context, intent,
            chargeCounterFallback = 3_000_000)
        assertEquals(4_800_000, captured.chargeCounterUah)
        intent.removeExtra("charge_counter")
        val unavailable = BatterySleepStore.snapshotFromIntent(context, intent,
            chargeCounterFallback = Int.MIN_VALUE)
        assertNull(unavailable.chargeCounterUah)
    }
}
