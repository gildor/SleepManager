package com.med.sleepmanager.history

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.med.sleepmanager.data.BatterySleepStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Service-owned recorder. Entry points run on the main looper, independent of UI refresh.
 * Captures battery broadcasts at observation time; counter normalization and SQLite writes
 * run on one worker in arrival order. No polling, alarms, wake locks or new permissions.
 */
internal class BehaviorHistoryRecorder(
    context: Context,
    initialLidClosed: Boolean? = null,
    persist: ((BehaviorObservation) -> Unit)? = null
) {
    private val context = context.applicationContext
    private val persist = persist ?: { observation: BehaviorObservation ->
        BehaviorHistoryStore.append(this.context, observation)
    }
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SleepManagerHistory")
    }
    private val handler = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val bootCount = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()
    private val recorderId = UUID.randomUUID().toString()
    private var writerRunId = UUID.randomUUID().toString()
    @Volatile private var running = false
    private var state = BehaviorState.STANDBY
    private var lidClosed = initialLidClosed
    private var displayState: Pair<Boolean?, Boolean?> = null to null
    private var batteryIntent: Intent? = null
    private var batteryKey: List<Int>? = null
    private var batteryRegistered = false
    private var displayRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            batteryIntent = Intent(intent)
            val key = batteryKey(intent)
            if (key == batteryKey) return
            batteryKey = key
            submit(BehaviorEvent.BATTERY, batteryIntent)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = displayChanged()
        override fun onDisplayRemoved(displayId: Int) = displayChanged()
        override fun onDisplayChanged(displayId: Int) = displayChanged()
    }

    fun start(initialState: BehaviorState) {
        if (running) return
        running = true
        state = initialState
        liveRun.set(LiveRun(recorderId, null))
        displayState = readDisplayState()
        batteryIntent = currentBatteryIntent()
        batteryKey = batteryIntent?.let(::batteryKey)
        submit(BehaviorEvent.START, batteryIntent)
        runCatching {
            ContextCompat.registerReceiver(
                context, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            batteryRegistered = true
        }.onFailure {
            submit(BehaviorEvent.CONTEXT)
            Log.w(TAG, "Battery history observation unavailable", it)
        }
        runCatching {
            displays?.registerDisplayListener(displayListener, handler)
            displayRegistered = displays != null
        }.onFailure {
            displayState = null to null
            submit(BehaviorEvent.CONTEXT)
            Log.w(TAG, "Display history observation unavailable", it)
        }
    }

    fun standby() = transition(BehaviorState.STANDBY)

    fun wake(suppressed: Boolean) {
        if (suppressed) {
            state = BehaviorState.STANDBY
            submit(BehaviorEvent.PROTECTED_WAKE)
        } else transition(BehaviorState.AWAKE)
    }

    fun protectedResleep() {
        submit(BehaviorEvent.RESLEEP)
    }

    fun lid(closed: Boolean?) {
        if (lidClosed == closed) return
        lidClosed = closed
        submit(BehaviorEvent.CONTEXT)
    }

    /** Stops observation immediately; the returned task lets off-main callers await persistence. */
    fun stop(): Future<*>? {
        if (!running) return null
        val completion = submit(BehaviorEvent.STOP)
        running = false
        liveRun.updateAndGet { if (it?.owner == recorderId) null else it }
        if (batteryRegistered) runCatching { context.unregisterReceiver(batteryReceiver) }
        if (displayRegistered) runCatching { displays?.unregisterDisplayListener(displayListener) }
        writer.shutdown() // Drain already accepted records; never wait on the main looper.
        return completion
    }

    private fun transition(target: BehaviorState) {
        val changed = target != state
        state = target
        submit(if (changed) BehaviorEvent.STATE_CHANGE else BehaviorEvent.CONTEXT)
    }

    private fun displayChanged() {
        val current = readDisplayState()
        if (current == displayState) return
        displayState = current
        submit(BehaviorEvent.CONTEXT)
    }

    private fun readDisplayState(): Pair<Boolean?, Boolean?> = runCatching {
        val available = displays?.displays ?: return@runCatching null to null
        // Same observable proxy as existing dock handling; not all USB docks expose a display.
        val external = available.filter {
            it.displayId != Display.DEFAULT_DISPLAY && it.name.lowercase().let { name ->
                "dp screen" in name || "hdmi" in name || "external" in name
            }
        }
        external.isNotEmpty() to external.any { it.state == Display.STATE_ON }
    }.getOrDefault(null to null)

    private fun currentBatteryIntent(): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()?.let(::Intent)

    private fun submit(event: BehaviorEvent, suppliedBattery: Intent? = null): Future<*>? {
        if (!running) return null
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val capturedState = state
        val capturedBattery = suppliedBattery?.let(::Intent) ?: currentBatteryIntent()
        if (capturedBattery != null &&
            capturedBattery.getIntExtra("charge_counter", Int.MIN_VALUE) < 0
        ) {
            val counter = runCatching {
                (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            }.getOrNull() ?: Int.MIN_VALUE
            capturedBattery.putExtra("charge_counter", counter)
        }
        val capturedContext = BehaviorContext(
            lidClosed = lidClosed,
            externalDisplayConnected = displayState.first,
            externalDisplayActive = displayState.second,
            externalPowerConnected = capturedBattery?.takeIf {
                (batteryRegistered || event == BehaviorEvent.START) &&
                it.hasExtra(BatteryManager.EXTRA_PLUGGED)
            }?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 }
        )
        return writer.submit {
            try {
                val battery = runCatching { readBattery(capturedBattery) }.getOrNull()
                persist(BehaviorObservation(
                    runId = writerRunId,
                    wallTimeMs = wall,
                    elapsedTimeMs = elapsed,
                    bootCount = bootCount,
                    event = event,
                    state = capturedState,
                    context = capturedContext,
                    battery = battery
                ))
                publishRun(writerRunId)
            } catch (e: Exception) {
                // A lost state change must not be bridged by the next successful write.
                writerRunId = UUID.randomUUID().toString()
                publishRun(null)
                Log.w(TAG, "History write failed; next observation starts new coverage", e)
            }
        }
    }

    private fun readBattery(intent: Intent?): BehaviorBattery? {
        if (intent == null) return null
        // Never re-read the current counter later on the writer thread: the sample
        // belongs to the captured transition, even if storage is temporarily busy.
        val snapshot = BatterySleepStore.snapshotFromIntent(
            context, intent, chargeCounterFallback = Int.MIN_VALUE
        )
        val selection = snapshot.capacitySelection
        return BehaviorBattery(
            percent = snapshot.percent,
            chargeUah = selection.displayedCurrentUah,
            capacityUah = selection.selectedFullUah,
            source = selection.currentSource.name,
            normalizationFullUah = snapshot.fullChargeUah,
            status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> BatteryChargeStatus.CHARGING
                BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryChargeStatus.DISCHARGING
                BatteryManager.BATTERY_STATUS_FULL -> BatteryChargeStatus.FULL
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryChargeStatus.NOT_CHARGING
                else -> BatteryChargeStatus.UNKNOWN
            }
        )
    }

    private fun batteryKey(intent: Intent): List<Int> = listOf(
        intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
        intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    )

    private fun publishRun(persistedRunId: String?) {
        liveRun.updateAndGet {
            if (it?.owner == recorderId) it.copy(persisted = persistedRunId) else it
        }
    }

    companion object {
        private const val TAG = "BehaviorHistory"
        private data class LiveRun(val owner: String, val persisted: String?)
        private val liveRun = AtomicReference<LiveRun?>(null)

        /** Process-local liveness only. A persisted run ID does not prove tracking survived. */
        val activeRunId: String?
            get() = liveRun.get()?.persisted
    }
}
