package com.med.sleepmanager.service

import androidx.core.content.ContextCompat
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.Executors
import com.med.sleepmanager.MainActivity
import com.med.sleepmanager.api.SleepManagerStatusProvider
import com.med.sleepmanager.R
import com.med.sleepmanager.data.AppPreferences
import com.med.sleepmanager.data.DiagnosticsCycleStore
import com.med.sleepmanager.data.DiagnosticsStateStore
import com.med.sleepmanager.data.DirectRadioStore
import com.med.sleepmanager.history.BehaviorHistoryRecorder
import com.med.sleepmanager.history.BehaviorState
import com.med.sleepmanager.data.BatterySleepStore
import com.med.sleepmanager.data.ClamshellStateStore
import com.med.sleepmanager.data.SleepCycleStore
import com.med.sleepmanager.data.RaOfflineProxySleepStore
import com.med.sleepmanager.device.DeviceControlController
import com.med.sleepmanager.device.DeviceControlStore
import com.med.sleepmanager.device.RadioBackend
import com.med.sleepmanager.device.RadioController
import com.med.sleepmanager.integration.BasicSyncController
import com.med.sleepmanager.integration.HelperController
import com.med.sleepmanager.integration.SyncthingController
import com.med.sleepmanager.integration.TailscaleController
import com.med.sleepmanager.integration.RaOfflineProxyController
import com.med.sleepmanager.integration.connector.BasicSyncConnector
import com.med.sleepmanager.integration.connector.RaOfflineProxyConnector
import com.med.sleepmanager.integration.connector.JamesDspConnector
import com.med.sleepmanager.integration.connector.SyncthingConnector
import com.med.sleepmanager.integration.connector.TailscaleConnector
import com.med.sleepmanager.network.NetworkReadyGate
import com.med.sleepmanager.protection.ClamshellMonitorRuntime
import com.med.sleepmanager.protection.ClosedLidAdmin
import com.med.sleepmanager.protection.LidMonitor
import com.med.sleepmanager.protection.PmicPowerButtonMonitor
import com.med.sleepmanager.rules.ClamshellDockPolicy
import com.med.sleepmanager.rules.BatterySaverPolicy
import com.med.sleepmanager.rules.BatterySaverPowerEventDecision
import com.med.sleepmanager.rules.BatterySaverRecoveryDecision
import com.med.sleepmanager.rules.BatterySaverRestoreDecision
import com.med.sleepmanager.rules.ChargingSeparationOwnedApplyDecision
import com.med.sleepmanager.rules.ChargingSeparationPolicy
import com.med.sleepmanager.rules.ChargingSeparationRecoveryDecision
import com.med.sleepmanager.rules.ChargingSeparationRestoreDecision
import com.med.sleepmanager.rules.ClamshellMonitoringPolicy
import com.med.sleepmanager.rules.DockDisconnectDecision
import com.med.sleepmanager.rules.HelperResultCorrelation
import com.med.sleepmanager.rules.HelperResultPolicy
import com.med.sleepmanager.rules.SleepConditionEvaluator
import com.med.sleepmanager.rules.SleepWakePolicy
import com.med.sleepmanager.sync.ManagedSyncProviders
import com.med.sleepmanager.sync.PeriodicAlarmDeviceDecision
import com.med.sleepmanager.sync.PeriodicMaintenanceFollowUp
import com.med.sleepmanager.sync.OwnedBasicSyncRestoreResult
import com.med.sleepmanager.sync.SyncMaintenancePolicy
import com.med.sleepmanager.sync.SyncMaintenanceRunner
import com.med.sleepmanager.sync.SyncMaintenanceScheduler
import com.med.sleepmanager.sync.SyncMaintenanceTrigger
import com.med.sleepmanager.sync.SyncStopOwnershipStore
import com.med.sleepmanager.sync.SyncTransitionStore
import com.med.sleepmanager.sync.SyncCompletionState
import com.med.sleepmanager.sync.basicSyncCompletionState

class SleepManagerService : Service() {
    companion object {
        private const val TAG = "SleepManager"
        private const val CHANNEL_ID = "sleep_manager"
        private const val NOTIFICATION_ID = 5217
        private const val NETWORK_READY_TIMEOUT_MS = 15000L
        private const val SYNCTHING_STOP_GRACE_MS = 1000L
        private const val SYNCTHING_UNVERIFIED_STOP_GRACE_MS = 2500L
        private const val SYNC_STOP_POLL_INTERVAL_MS = 250L
        private const val SYNC_STOP_TIMEOUT_MS = 5_000L
        private const val BASIC_SYNC_FRESH_STATE_TIMEOUT_MS = 1_500L
        private const val BASIC_SYNC_WAKE_RESTORE_CONFIRM_INTERVAL_MS = 250L
        private const val BASIC_SYNC_WAKE_RESTORE_CONFIRM_TIMEOUT_MS = 5_000L
        private const val BASIC_SYNC_ACTIVE_FINISH_TIMEOUT_MS = 120_000L
        private const val BASIC_SYNC_ACTIVE_FINISH_POLL_MS = 500L
        private const val BASIC_SYNC_IDLE_STABILITY_MS = 3_000L
        private const val DEVICE_CONTROL_RESTORE_INTERVAL_MS = 500L
        private const val DEVICE_CONTROL_RESTORE_MAX_ATTEMPTS = 6
        private const val TAILSCALE_VERIFY_INTERVAL_MS = 500L
        private const val TAILSCALE_VERIFY_MAX_ATTEMPTS = 8
        private const val TAILSCALE_WAKE_RETRY_AT_ATTEMPT = 4
        private const val TAILSCALE_WAKE_MAX_ATTEMPTS = 12
        private const val SLEEP_TRANSITION_WAKELOCK_TIMEOUT_MS = 8_000L
        private const val SYNCTHING_PRE_SLEEP_PROBE_TIMEOUT_MS = 1_500L
        private const val OWNED_BASIC_SYNC_RESTORE_INTERVAL_MS = 250L
        private const val OWNED_BASIC_SYNC_RESTORE_MAX_ATTEMPTS = 8
        private const val HELPER_RESTORE_RETRY_DELAY_MS = 2_000L
        private const val HELPER_RESTORE_MAX_ATTEMPTS = 3
        private const val CLOSED_LID_GUARD_DELAY_MS = 1500L
        private const val CLOSED_LID_SCREEN_ON_RECHECK_DELAY_MS = 500L
        private const val DOCK_DISCONNECT_DEBOUNCE_MS = 500L
        private const val EXTERNAL_POWER_DISCONNECT_RECHECK_MS = 2_000L
        private const val CLOSED_LID_LOCK_COOLDOWN_MS = 900L
        const val ACTION_DISABLE_AND_RESTORE =
            "com.med.sleepmanager.action.DISABLE_AND_RESTORE"
        const val ACTION_SLEEP_DELAY_ELAPSED =
            "com.med.sleepmanager.action.SLEEP_DELAY_ELAPSED"
        const val ACTION_PERIODIC_SYNC =
            "com.med.sleepmanager.action.PERIODIC_SYNC"
        private const val SLEEP_DELAY_REQUEST_CODE = 5218

        @Volatile
        var running: Boolean = false
            private set
    }

    private enum class SyncthingSleepSummaryState {
        NOT_MANAGED,
        STOP_NOT_SENT,
        STOP_SENT,
        STOP_CONFIRMED,
        STOP_UNVERIFIED,
        STOP_NOT_CONFIRMED
    }

    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var helperResultReceiverRegistered = false

    private val helperWakeResultState = HelperWakeResultState()
    private var syncthingSleepSummaryState =
        SyncthingSleepSummaryState.NOT_MANAGED
    private val sleepCycleRuntimeState = SleepCycleRuntimeState()
    private val basicSyncPreSleepState = BasicSyncPreSleepState()
    private val syncthingPreSleepState = SyncthingPreSleepState()

    private val activeBasicSyncFinishRunnable = Runnable {
        pollActiveBasicSyncBeforeSleep()
    }

    private val externalPowerDisconnectRecheckRunnable = Runnable {
        val stillConnected =
            DeviceControlController.externalPowerConnected(this)
        if (stillConnected) {
            Log.i(
                TAG,
                "Ignoring transient power disconnect; external power is still connected"
            )
            handleExternalPowerChanged(connected = true)
        } else {
            handleExternalPowerChanged(connected = false)
        }
    }

    private val sleepStopWaitState = SleepStopWaitState()
    private val syncStopProbeExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticsExecutor = Executors.newSingleThreadExecutor()
    private var raOfflineProxyCoordinator: RaOfflineProxyCoordinator? = null
    private var raOfflineProxyRestoreInFlight = false
    private val sleepDelayState = SleepDelayState()
    private var sleepTransitionWakeLock: PowerManager.WakeLock? = null
    private var networkReadyGate: NetworkReadyGate? = null
    private val helperNetworkRestoreHandoffState =
        HelperNetworkRestoreHandoffState()
    private val helperRestoreRetryState =
        HelperRestoreRetryState()
    private val helperRestoreRetryRunnable = Runnable {
        retryPendingHelperRestoreAcknowledgement()
    }
    private val tailscaleVerificationState =
        TailscaleVerificationRuntimeState()
    private var initialScreenStateApplied = false
    private var syncMaintenanceRunner: SyncMaintenanceRunner? = null
    private val wakeTransitionSyncState = WakeTransitionSyncState()
    private val basicSyncRestoreRetryState =
        BasicSyncRestoreRetryState()
    private val deviceControlRestoreRetryState =
        DeviceControlRestoreRetryState()
    private val disableRestoreState = DisableRestoreRuntimeState()

    private var basicSyncWakeRestoreStartedAt = 0L
    private val basicSyncWakeRestoreRunnable = Runnable {
        verifyPendingBasicSyncRestore()
    }

    private val ownedBasicSyncRestoreRunnable = Runnable {
        maybeRestoreOwnedBasicSyncState()
    }

    private val ownedDeviceControlRestoreRunnable = Runnable {
        continueOwnedDeviceControlRestoreForDisable()
    }

    private fun armHelperRestoreRetry(
        cycleId: Long,
        requestSent: Boolean
    ) {
        handler.removeCallbacks(helperRestoreRetryRunnable)
        helperRestoreRetryState.begin(
            cycleId = cycleId,
            requestSent = requestSent
        )
        if (helperRestoreRetryState.pending) {
            handler.postDelayed(
                helperRestoreRetryRunnable,
                HELPER_RESTORE_RETRY_DELAY_MS
            )
        }
    }

    private fun clearHelperRestoreRetry(resultCycleId: Long = 0L) {
        helperRestoreRetryState.acknowledge(resultCycleId)
        if (!helperRestoreRetryState.pending) {
            handler.removeCallbacks(helperRestoreRetryRunnable)
        }
    }

    private fun retryPendingHelperRestoreAcknowledgement() {
        val cycle = SleepCycleStore.current(this)
        val stillNeedsRestore =
            cycle.active &&
                cycle.helperExpected &&
                cycle.helperSleepRequested &&
                !cycle.helperRestored

        when (
            helperRestoreRetryState.decision(
                activeCycleId = cycle.cycleId,
                stillNeedsRestore = stillNeedsRestore,
                maxAttempts = HELPER_RESTORE_MAX_ATTEMPTS
            )
        ) {
            HelperRestoreRetryDecision.STALE -> {
                helperRestoreRetryState.clear()
                return
            }

            HelperRestoreRetryDecision.EXHAUSTED -> {
                val attempts = helperRestoreRetryState.attempts
                helperRestoreRetryState.clear()
                SleepCycleStore.markRestoreProblem(
                    this,
                    "Compatibility Helper restore acknowledgement is still pending."
                )
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Helper reconciliation → no acknowledgement after $attempts attempts"
                )
                Log.w(
                    TAG,
                    "Helper restore acknowledgement retries exhausted for cycle=${cycle.cycleId}"
                )
                finishDisableRestoreIfRequested(forceStop = true)
                return
            }

            HelperRestoreRetryDecision.RETRY -> Unit
        }

        val sent =
            if (disableRestoreState.isRequested) {
                HelperController.restoreNow(this, cycle.cycleId)
            } else {
                HelperController.sendWake(this, cycle.cycleId)
            }

        if (!sent) {
            helperRestoreRetryState.clear()
            SleepCycleStore.markRestoreProblem(
                this,
                "Compatibility Helper is unavailable, so Wi-Fi / Bluetooth restore is still pending."
            )
            DiagnosticsStateStore.recordEvent(
                this,
                "Helper reconciliation → replay could not be sent"
            )
            finishDisableRestoreIfRequested(forceStop = true)
            return
        }

        helperRestoreRetryState.recordRetrySent()
        DiagnosticsStateStore.recordEvent(
            this,
            "Helper reconciliation → replayed cycle ${cycle.cycleId} " +
                "attempt ${helperRestoreRetryState.attempts}"
        )
        handler.postDelayed(
            helperRestoreRetryRunnable,
            HELPER_RESTORE_RETRY_DELAY_MS
        )
    }

    private fun raOfflineProxyCoordinator(): RaOfflineProxyCoordinator =
        raOfflineProxyCoordinator
            ?: RaOfflineProxyCoordinator(
                context = this,
                handler = handler,
                canContinueSleepGate = { cycleId ->
                    val cycle = SleepCycleStore.current(this)
                    cycle.active &&
                        cycle.cycleId == cycleId &&
                        isEffectivelySleepingNow() &&
                        AppPreferences.isEnabled(this)
                },
                onSleepGateReady = { cycleId, wifi, bluetooth ->
                    val cycle = SleepCycleStore.current(this)
                    if (
                        cycle.active &&
                        cycle.cycleId == cycleId &&
                        isEffectivelySleepingNow() &&
                        AppPreferences.isEnabled(this)
                    ) {
                        Log.i(
                            TAG,
                            "RAOfflineProxy gate complete -> applying sleep connectivity"
                        )
                        applySleepConnectivity(
                            wifi = wifi,
                            bluetooth = bluetooth
                        )
                        // Safe no-op while Helper or connector ownership is
                        // still pending. Clears an otherwise empty transaction.
                        SleepCycleStore.completeIfRestored(this)
                    } else {
                        Log.i(
                            TAG,
                            "Ignoring stale RAOfflineProxy sleep-gate completion for cycle=" +
                                cycleId
                        )
                    }
                }
            ).also {
                raOfflineProxyCoordinator = it
            }

    private fun syncRunner(): SyncMaintenanceRunner =
        syncMaintenanceRunner
            ?: SyncMaintenanceRunner(this, handler).also {
                syncMaintenanceRunner = it
            }

    private fun cancelSyncMaintenance(restoreSleepWifi: Boolean) {
        syncMaintenanceRunner?.cancel(restoreSleepWifi)
    }

    private fun captureAdvancedDiagnostics(
        phase: String,
        includeLatestProcessExit: Boolean = false,
        trimMemoryLevel: Int? = null
    ) {
        if (!AppPreferences.advancedDiagnosticsEnabled(this)) return

        val appContext = applicationContext
        runCatching {
            diagnosticsExecutor.execute {
                DiagnosticsCycleStore.captureSystemSnapshot(
                    context = appContext,
                    phase = phase,
                    includeDetailedProcessMemory = true,
                    includeLatestProcessExit = includeLatestProcessExit,
                    trimMemoryLevel = trimMemoryLevel
                )
            }
        }.onFailure {
            Log.w(TAG, "Advanced diagnostics capture skipped", it)
        }
    }

    private fun refreshBasicSyncObserver() {
        if (AppPreferences.manageBasicSync(this)) {
            BasicSyncController.startStateObserver(this)
        } else {
            BasicSyncController.stopStateObserver()
        }
    }

    private fun shouldRestoreOwnedBasicSyncState(): Boolean =
        SyncStopOwnershipStore.hasBasicSyncOwnership(this) &&
            (
                !AppPreferences.isEnabled(this) ||
                    !AppPreferences.manageBasicSync(this) ||
                    !AppPreferences.syncThenStopOnSleepWake(this)
            )

    private fun maybeRestoreOwnedBasicSyncState() {
        handler.removeCallbacks(ownedBasicSyncRestoreRunnable)

        if (!shouldRestoreOwnedBasicSyncState()) {
            basicSyncRestoreRetryState.reset()
            return
        }

        basicSyncRestoreRetryState.markAttemptStarted()

        // The integration may have just been disabled, so keep a temporary
        // state observer alive long enough to verify that SleepManager still
        // owns the stopped state before restoring anything.
        BasicSyncController.startStateObserver(this)

        val restoreResult =
            SyncStopOwnershipStore.restoreBasicSyncIfOwned(this)
        when (
            basicSyncRestoreRetryState.onRestoreResult(
                retryable =
                    restoreResult == OwnedBasicSyncRestoreResult.PENDING ||
                        restoreResult == OwnedBasicSyncRestoreResult.FAILED,
                maxAttempts = OWNED_BASIC_SYNC_RESTORE_MAX_ATTEMPTS
            )
        ) {
            BasicSyncRestoreRetryDecision.RETRY -> {
                handler.postDelayed(
                    ownedBasicSyncRestoreRunnable,
                    OWNED_BASIC_SYNC_RESTORE_INTERVAL_MS
                )
                return
            }

            BasicSyncRestoreRetryDecision.EXHAUSTED -> {
                Log.w(
                    TAG,
                    "BasicSync original state restore remains pending"
                )
                DiagnosticsStateStore.recordEvent(
                    this,
                    "BasicSync → original state restore retries exhausted"
                )
                if (disableRestoreState.isRequested) {
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Disable → BasicSync original state restore pending"
                    )
                }
            }

            BasicSyncRestoreRetryDecision.COMPLETE -> Unit
        }

        if (!AppPreferences.manageBasicSync(this)) {
            BasicSyncController.stopStateObserver()
        }

        finishDisableRestoreIfRequested()
    }

    private val clamshellLidState = ClamshellLidState()

    private val clamshellMonitorRuntime = ClamshellMonitorRuntime()
    private val clamshellDisplayState = ClamshellDisplayState()
    private val closedLidState = ClosedLidRuntimeState()

    private val displayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }

    private val closedLidAdminComponent by lazy {
        ClosedLidAdmin.component(this)
    }

    private val sleepGraceRunnable = Runnable {
        sleepDelayState.clear()
        Log.i(TAG, "Sleep delay elapsed -> applying sleep actions")
        performFreshSleepActions()
    }

    private val sleepRadioRunnable = Runnable {
        continueSleepRadioAfterSyncStop()
    }

    private val tailscaleSleepVerifyRunnable = Runnable {
        verifyTailscaleSleepDisconnect()
    }

    private val tailscaleWakeVerifyRunnable = Runnable {
        verifyTailscaleWakeReconnect()
    }

    private val closedLidGuardRunnable = Runnable {
        maybeReturnDeviceToSleep("close guard")
    }

    private val closedLidScreenOnRecheckRunnable = Runnable {
        maybeReturnDeviceToSleep("closed-lid wake")
    }

    private val dockDisconnectRunnable = Runnable {
        handleDockDisconnect()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshExternalDisplayState("display added")
        }

        override fun onDisplayRemoved(displayId: Int) {
            refreshExternalDisplayState("display removed")
        }

        override fun onDisplayChanged(displayId: Int) {
            refreshExternalDisplayState("display changed")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_POWER_CONNECTED -> {
                    handler.removeCallbacks(
                        externalPowerDisconnectRecheckRunnable
                    )
                    BatterySleepStore.noteCharging(this@SleepManagerService)
                    handleExternalPowerChanged(connected = true)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    handler.removeCallbacks(
                        externalPowerDisconnectRecheckRunnable
                    )
                    handler.postDelayed(
                        externalPowerDisconnectRecheckRunnable,
                        EXTERNAL_POWER_DISCONNECT_RECHECK_MS
                    )
                }
            }
        }
    }

    private val helperResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action != HelperController.ACTION_RESULT &&
                intent?.action != HelperController.ACTION_RESULT_V2
            ) {
                return
            }

            val phase = intent.getStringExtra(HelperController.EXTRA_PHASE) ?: return
            val resultCycleId =
                intent.getLongExtra(HelperController.EXTRA_CYCLE_ID, 0L)
            val activeCycle = SleepCycleStore.current(this@SleepManagerService)
            when (
                HelperResultPolicy.correlate(
                    resultCycleId = resultCycleId,
                    cycleActive = activeCycle.active,
                    currentCycleId = activeCycle.cycleId
                )
            ) {
                HelperResultCorrelation.STALE -> {
                    Log.w(
                        TAG,
                        "Ignoring stale Helper result phase=$phase resultCycle=$resultCycleId " +
                            "activeCycle=${activeCycle.cycleId} active=${activeCycle.active}"
                    )
                    DiagnosticsStateStore.recordEvent(
                        this@SleepManagerService,
                        "Helper result ignored · stale cycle $resultCycleId"
                    )
                    return
                }

                HelperResultCorrelation.LEGACY_UNCORRELATED -> {
                    Log.i(
                        TAG,
                        "Legacy Helper result without cycleId accepted for phase=$phase"
                    )
                }

                HelperResultCorrelation.CURRENT -> Unit
            }

            if (phase == HelperController.PHASE_WAKE) {
                clearHelperRestoreRetry(resultCycleId)
            }

            val wifiManaged = intent.getBooleanExtra(HelperController.EXTRA_WIFI_MANAGED, false)
            val wifiChanged = intent.getBooleanExtra(HelperController.EXTRA_WIFI_CHANGED, false)
            val wifiAttempted =
                intent.getBooleanExtra(HelperController.EXTRA_WIFI_ATTEMPTED, false)
            val wifiAction =
                intent.getStringExtra(HelperController.EXTRA_WIFI_ACTION) ?: "NONE"
            val wifiToggleSuccess =
                intent.getBooleanExtra(HelperController.EXTRA_WIFI_TOGGLE_SUCCESS, true)
            val airplaneMode =
                intent.getBooleanExtra(HelperController.EXTRA_AIRPLANE_MODE, false)
            val bluetoothManaged = intent.getBooleanExtra(HelperController.EXTRA_BLUETOOTH_MANAGED, false)
            val bluetoothChanged = intent.getBooleanExtra(HelperController.EXTRA_BLUETOOTH_CHANGED, false)
            val restoreSuccess = intent.getBooleanExtra(
                HelperController.EXTRA_RESTORE_SUCCESS,
                true
            )
            val helperStatus =
                intent.getStringExtra(HelperController.EXTRA_STATUS)
                    ?: HelperController.STATUS_OK

            if (
                wifiManaged &&
                helperStatus != HelperController.STATUS_ALREADY_SLEEPING
            ) {
                DiagnosticsStateStore.recordWifiToggleDiagnostic(
                    context = this@SleepManagerService,
                    phase = phase,
                    action = wifiAction,
                    attempted = wifiAttempted,
                    success = wifiToggleSuccess,
                    airplaneMode = airplaneMode
                )
            }

            when (phase) {
                HelperController.PHASE_SLEEP -> {
                    if (helperStatus == HelperController.STATUS_CYCLE_MISMATCH) {
                        releaseSleepTransitionWakeLock()
                        Log.w(
                            TAG,
                            "Helper rejected sleep request because its active cycle differs"
                        )
                        DiagnosticsStateStore.recordEvent(
                            this@SleepManagerService,
                            "Sleep → Helper cycle mismatch; radio sleep skipped"
                        )
                        return
                    }

                    if (SleepCycleStore.isActive(this@SleepManagerService)) {
                        SleepCycleStore.markHelperSleepRequested(this@SleepManagerService)
                    }
                    releaseSleepTransitionWakeLock()
                    DiagnosticsStateStore.recordEvent(
                        this@SleepManagerService,
                        buildSleepSummary(
                            wifiManaged = wifiManaged,
                            wifiChanged = wifiChanged,
                            wifiAttempted = wifiAttempted,
                            wifiToggleSuccess = wifiToggleSuccess,
                            wifiAirplaneMode = airplaneMode,
                            bluetoothManaged = bluetoothManaged,
                            bluetoothChanged = bluetoothChanged
                        )
                    )
                }

                HelperController.PHASE_WAKE -> {
                    helperWakeResultState.record(
                        wifiManaged = wifiManaged,
                        wifiChanged = wifiChanged,
                        wifiAttempted = wifiAttempted,
                        wifiToggleSuccess = wifiToggleSuccess,
                        wifiAirplaneMode = airplaneMode,
                        bluetoothManaged = bluetoothManaged,
                        bluetoothChanged = bluetoothChanged
                    )

                    if (!restoreSuccess) {
                        wakeTransitionSyncState.clear()
                        Log.w(
                            TAG,
                            "Helper restore failed status=$helperStatus; preserving sleep transaction"
                        )

                        val wifiRestoreFailed =
                            wifiManaged && wifiAttempted && !wifiToggleSuccess
                        val wifiFailureText =
                            if (wifiRestoreFailed) {
                                "Wi-Fi toggle failed" +
                                    if (airplaneMode) {
                                        " · Airplane mode is enabled"
                                    } else {
                                        ""
                                    }
                            } else {
                                null
                            }

                        SleepCycleStore.markRestoreProblem(
                            this@SleepManagerService,
                            when {
                                helperStatus == HelperController.STATUS_NO_ACTIVE_CYCLE ->
                                    "Compatibility Helper no longer has the pending sleep state."
                                helperStatus == HelperController.STATUS_CYCLE_MISMATCH ->
                                    "Compatibility Helper is tracking a different sleep cycle."
                                wifiFailureText != null ->
                                    "$wifiFailureText. Wi-Fi restore is still pending."
                                else ->
                                    "Compatibility Helper could not restore Wi-Fi / Bluetooth."
                            }
                        )
                        DiagnosticsStateStore.recordEvent(
                            this@SleepManagerService,
                            if (wifiFailureText != null) {
                                val prefix =
                                    if (disableRestoreState.isRequested) "Disable" else "Wake"
                                "$prefix → $wifiFailureText"
                            } else if (disableRestoreState.isRequested) {
                                "Disable → Helper restore pending"
                            } else {
                                "Wake → Helper restore pending"
                            }
                        )
                        finishDisableRestoreIfRequested(forceStop = true)
                        return
                    }

                    if (SleepCycleStore.isActive(this@SleepManagerService)) {
                        SleepCycleStore.markHelperRestored(this@SleepManagerService)
                    }

                    val restoreNetworkConnectors =
                        helperNetworkRestoreHandoffState.consume()

                    if (restoreNetworkConnectors && hasPendingNetworkConnectorRestore()) {
                        Log.i(
                            TAG,
                            "Helper wake completed -> starting network-ready wait"
                        )
                        waitForNetworkAndRestorePendingConnectors()
                        maybeStartWakeTransitionSync()
                    } else {
                        if (!disableRestoreState.isRequested) {
                            // The Helper result is authoritative for radio restoration.
                            // Log it immediately even while independent connector restores
                            // (for example BasicSync or RAOfflineProxy) are still pending.
                            DiagnosticsStateStore.recordEvent(
                                this@SleepManagerService,
                                buildWakeSummary(
                                    wifiManaged = wifiManaged,
                                    wifiChanged = wifiChanged,
                                    wifiAttempted = wifiAttempted,
                                    wifiToggleSuccess = wifiToggleSuccess,
                                    wifiAirplaneMode = airplaneMode,
                                    bluetoothManaged = bluetoothManaged,
                                    bluetoothChanged = bluetoothChanged,
                                    syncthing = false
                                )
                            )
                        }

                        SleepCycleStore.completeIfRestored(this@SleepManagerService)
                        finishDisableRestoreIfRequested()
                        maybeStartWakeTransitionSync()
                    }
                }
            }
        }
    }

    private val helperResultReceiverV2 = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            helperResultReceiver.onReceive(context, intent)
        }
    }

    private var behaviorHistory: BehaviorHistoryRecorder? = null
    private var observedHistoryLidClosed: Boolean? = null

    private fun startBehaviorHistory() {
        if (behaviorHistory != null || !AppPreferences.isEnabled(this)) return
        behaviorHistory = BehaviorHistoryRecorder(this, observedHistoryLidClosed).also {
            it.start(if (isEffectivelySleepingNow()) BehaviorState.STANDBY else BehaviorState.AWAKE)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        SleepManagerStatusProvider.notifyChanged(this)
        createNotificationChannel()
        startForegroundCompat()
        registerScreenReceiver()
        registerHelperResultReceiver()
        refreshBasicSyncObserver()
        maybeRestoreOwnedBasicSyncState()
        maybeRestoreDisabledRaOfflineProxyState()
        refreshLidMonitor()
        startBehaviorHistory()
        recoverOwnedDeviceControls()
        recoverInterruptedSleepWifiMaintenance()
        Log.i(TAG, "Service started")
    }

    private fun recoverOwnedDeviceControls() {
        val batterySaverOwned =
            DeviceControlStore.batterySaver(this)

        if (batterySaverOwned.owned) {
            val realWake = isRealWakeNow()
            val effectivelySleeping =
                if (!realWake) isEffectivelySleepingNow() else null
            val externalPowerConnected =
                if (!realWake && effectivelySleeping == true) {
                    DeviceControlController.externalPowerConnected(this)
                } else {
                    null
                }
            val manageEnabled =
                if (
                    !realWake &&
                    effectivelySleeping == true &&
                    externalPowerConnected != true &&
                    !batterySaverOwned.previous
                ) {
                    AppPreferences.manageBatterySaver(this)
                } else {
                    null
                }
            val currentlyEnabled =
                if (
                    !realWake &&
                    effectivelySleeping == true &&
                    externalPowerConnected != true &&
                    !batterySaverOwned.previous &&
                    manageEnabled == true
                ) {
                    DeviceControlController.batterySaverEnabled(this)
                } else {
                    null
                }

            when (
                BatterySaverPolicy.recoveryDecision(
                    owned = true,
                    realWake = realWake,
                    effectivelySleeping = effectivelySleeping,
                    previous = batterySaverOwned.previous,
                    manageEnabled = manageEnabled,
                    currentlyEnabled = currentlyEnabled,
                    externalPowerConnected = externalPowerConnected
                )
            ) {
                BatterySaverRecoveryDecision.NOTHING -> Unit

                BatterySaverRecoveryDecision.RESTORE_PREVIOUS -> {
                    val restored = restoreOwnedBatterySaver("Recovery")
                    if (
                        restored &&
                        externalPowerConnected == true &&
                        !batterySaverOwned.previous &&
                        AppPreferences.manageBatterySaver(this)
                    ) {
                        DeviceControlStore.setBatterySaverDeferredForExternalPower(
                            this,
                            deferred = true
                        )
                    }
                }

                BatterySaverRecoveryDecision.REAPPLY_SLEEP_STATE -> {
                    if (
                        DeviceControlController
                            .setBatterySaverEnabled(true)
                    ) {
                        Log.i(
                            TAG,
                            "Recovery -> Battery Saver sleep state re-applied"
                        )
                    }
                }
            }
        }

        if (
            !DeviceControlStore.batterySaver(this).owned &&
            DeviceControlStore.batterySaverDeferredForExternalPower(this)
        ) {
            handleExternalPowerChanged(
                connected =
                    DeviceControlController.externalPowerConnected(this)
            )
        }

        val chargingOwned =
            DeviceControlStore.chargingSeparation(this)
        if (chargingOwned.owned) {
            val manageWithLid =
                AppPreferences.manageChargingSeparationWithLid(this)
            val lidSupported =
                if (manageWithLid) LidMonitor.isSupported() else false
            val lidClosed =
                manageWithLid && lidSupported && clamshellLidState.isClosed
            val externalDisplayConnected =
                if (lidClosed) hasExternalDisplayConnected() else false
            val shouldRemainOff =
                ChargingSeparationPolicy.shouldRemainDisabledDuringRecovery(
                    manageWithLid = manageWithLid,
                    lidSupported = lidSupported,
                    lidClosed = lidClosed,
                    externalDisplayConnected = externalDisplayConnected
                )
            val current =
                if (shouldRemainOff) {
                    DeviceControlController.chargingSeparationState(this)
                } else {
                    null
                }

            when (
                ChargingSeparationPolicy.recoveryDecision(
                    owned = true,
                    shouldRemainDisabled = shouldRemainOff,
                    current = current
                )
            ) {
                ChargingSeparationRecoveryDecision.NOTHING,
                ChargingSeparationRecoveryDecision.ALREADY_DISABLED -> Unit

                ChargingSeparationRecoveryDecision.RESTORE_PREVIOUS ->
                    restoreOwnedChargingSeparation("Recovery")

                ChargingSeparationRecoveryDecision.REAPPLY_DISABLED -> {
                    if (
                        DeviceControlController
                            .setChargingSeparationEnabled(false)
                    ) {
                        Log.i(
                            TAG,
                            "Recovery -> Charging Separation closed-lid state re-applied"
                        )
                    }
                }
            }
        }
    }

    private fun recoverInterruptedSleepWifiMaintenance() {
        if (!isEffectivelySleepingNow()) return

        val cycle = SleepCycleStore.current(this)
        if (
            cycle.active &&
            RaOfflineProxySleepStore.isPendingForCycle(
                this,
                cycle.cycleId
            )
        ) {
            Log.i(
                TAG,
                "Service recovery -> resuming RAOfflineProxy pre-sleep gate"
            )
            raOfflineProxyCoordinator().resumeSleepGate(cycle.cycleId)
            return
        }
        if (
            cycle.active &&
            DirectRadioStore.isPendingForCycle(this, cycle.cycleId)
        ) {
            val reapplied =
                RadioController.reapplyDirectSleepState(
                    this,
                    cycle.cycleId
                )
            DiagnosticsStateStore.recordEvent(
                this,
                "Recovery → PServer sleep radio state " +
                    if (reapplied) "re-applied" else "re-apply failed"
            )
            return
        }
        if (
            cycle.active &&
            cycle.helperExpected &&
            cycle.wifiManaged &&
            !cycle.helperRestored &&
            HelperController.isInstalled(this)
        ) {
            // Safe and idempotent: Helper 1.1+ accepts this only when it still
            // owns the Wi-Fi change from the active sleep cycle.
            HelperController.setTemporaryWifi(this, enabled = false)
            Log.i(
                TAG,
                "Service recovery -> requested sleep Wi-Fi state after interrupted maintenance"
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && AppPreferences.isEnabled(this)) {
            val activeCycle = SleepCycleStore.isActive(this)
            val message =
                if (activeCycle) {
                    "Foreground service restarted with an active sleep transaction"
                } else {
                    "Foreground service restarted by Android"
                }
            DeviceControlStore.recordServiceRecovery(this, message)
            if (activeCycle) {
                captureAdvancedDiagnostics(
                    phase = DiagnosticsCycleStore.PHASE_SERVICE_RECOVERY,
                    includeLatestProcessExit = true
                )
            }
            DiagnosticsStateStore.recordEvent(
                this,
                if (activeCycle) {
                    "Recovery → service restarted · transaction resumed"
                } else {
                    "Recovery → service restarted"
                }
            )
            Log.i(TAG, message)
        }

        if (intent?.action == ACTION_DISABLE_AND_RESTORE) {
            raOfflineProxyCoordinator?.cancelSleepGate(
                clearPersistedState = true
            )
            beginDisableAndRestore()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_PERIODIC_SYNC) {
            handlePeriodicSyncAlarm()
            return START_STICKY
        }

        if (intent?.action == ACTION_SLEEP_DELAY_ELAPSED) {
            cancelSleepDelay()

            if (!AppPreferences.isEnabled(this)) {
                stopSelf()
                return START_NOT_STICKY
            }

            if (isEffectivelySleepingNow() && !SleepCycleStore.isActive(this)) {
                Log.i(TAG, "Custom sleep delay elapsed -> evaluating advanced rules")
                performFreshSleepActions()
            }
            return START_STICKY
        }

        if (!AppPreferences.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!receiverRegistered) registerScreenReceiver()
        if (!helperResultReceiverRegistered) registerHelperResultReceiver()
        refreshBasicSyncObserver()
        maybeRestoreOwnedBasicSyncState()
        refreshLidMonitor()
        startBehaviorHistory()

        if (!initialScreenStateApplied) {
            initialScreenStateApplied = true
            applyCurrentScreenState()
        }

        return START_STICKY
    }

    private fun maybeRestoreDisabledRaOfflineProxyState() {
        if (AppPreferences.manageRaOfflineProxy(this)) return
        restorePendingRaOfflineProxyImmediately()
    }

    private fun restorePendingRaOfflineProxyImmediately() {
        if (raOfflineProxyRestoreInFlight) return
        if (!disableRestoreState.isRequested && !isRealWakeNow()) return

        val change =
            SleepCycleStore.connectorChange(
                this,
                RaOfflineProxyConnector.id
            )
        if (
            change?.restoreToken !=
            RaOfflineProxyConnector.TOKEN_RESTART
        ) {
            return
        }

        raOfflineProxyRestoreInFlight = true
        Log.i(
            TAG,
            "RAOfflineProxy restore starting immediately; network is not required"
        )

        raOfflineProxyCoordinator().restoreOwned { success, detail ->
            raOfflineProxyRestoreInFlight = false

            if (success) {
                DiagnosticsStateStore.recordEvent(
                    this,
                    if (disableRestoreState.isRequested) {
                        "Disable → RAOfflineProxy restored"
                    } else {
                        "Wake → RAOfflineProxy restored"
                    }
                )
            } else {
                SleepCycleStore.markRestoreProblem(
                    this,
                    "RAOfflineProxy restore is still pending: $detail."
                )
                DiagnosticsStateStore.recordEvent(
                    this,
                    if (disableRestoreState.isRequested) {
                        "Disable → RAOfflineProxy restore pending · $detail"
                    } else {
                        "Wake → RAOfflineProxy restore pending · $detail"
                    }
                )
            }

            SleepCycleStore.completeIfRestored(this)
            finishDisableRestoreIfRequested(
                forceStop =
                    disableRestoreState.isRequested && !success
            )
        }
    }

    private fun startOwnedDeviceControlRestoreForDisable() {
        handler.removeCallbacks(ownedDeviceControlRestoreRunnable)

        val batteryRestored =
            restoreOwnedBatterySaver("Disable")
        val chargingRestored =
            restoreOwnedChargingSeparation("Disable")

        if (
            deviceControlRestoreRetryState.begin(
                restored = batteryRestored && chargingRestored
            ) == DeviceControlRestoreRetryDecision.RETRY
        ) {
            handler.postDelayed(
                ownedDeviceControlRestoreRunnable,
                DEVICE_CONTROL_RESTORE_INTERVAL_MS
            )
        }
    }

    private fun continueOwnedDeviceControlRestoreForDisable() {
        if (!disableRestoreState.isRequested) {
            deviceControlRestoreRetryState.cancel()
            return
        }

        val batteryRestored =
            restoreOwnedBatterySaver("Disable retry")
        val chargingRestored =
            restoreOwnedChargingSeparation("Disable retry")

        when (
            deviceControlRestoreRetryState.onRetryResult(
                restored = batteryRestored && chargingRestored,
                maxAttempts = DEVICE_CONTROL_RESTORE_MAX_ATTEMPTS
            )
        ) {
            DeviceControlRestoreRetryDecision.COMPLETE -> {
                finishDisableRestoreIfRequested()
            }

            DeviceControlRestoreRetryDecision.RETRY -> {
                handler.postDelayed(
                    ownedDeviceControlRestoreRunnable,
                    DEVICE_CONTROL_RESTORE_INTERVAL_MS
                )
            }

            DeviceControlRestoreRetryDecision.EXHAUSTED -> {
                SleepCycleStore.markRestoreProblem(
                    this,
                    "A system setting changed by SleepManager could not be restored."
                )
                if (DeviceControlStore.batterySaver(this).owned) {
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Disable → Battery Saver restore retries exhausted"
                    )
                }
                if (DeviceControlStore.chargingSeparation(this).owned) {
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Disable → Charging Separation restore retries exhausted"
                    )
                }
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Disable → system setting restore pending"
                )
                finishDisableRestoreIfRequested(forceStop = true)
            }
        }
    }

    private fun beginDisableAndRestore() {
        behaviorHistory?.stop()
        behaviorHistory = null
        disableRestoreState.begin()
        DeviceControlStore.setBatterySaverDeferredForExternalPower(
            this,
            deferred = false
        )
        wakeTransitionSyncState.clear()
        SyncTransitionStore.clear(this)
        syncthingPreSleepState.invalidate()
        raOfflineProxyCoordinator?.cancelSleepGate(
            clearPersistedState = true
        )
        RaOfflineProxySleepStore.clear(this)

        cancelNetworkReadyWait()

        SyncMaintenanceScheduler.cancel(this)
        cancelSyncMaintenance(restoreSleepWifi = false)
        cancelSleepDelay()
        clearPendingSleepStopWait()
        sleepCycleRuntimeState.clearSkippedByConditions()
        releaseSleepTransitionWakeLock()

        startOwnedDeviceControlRestoreForDisable()
        maybeRestoreOwnedBasicSyncState()
        prepareTailscaleVerificationForWake()
        restorePendingJamesDsp()
        restorePendingBasicSync()
        restorePendingRaOfflineProxyImmediately()

        var cycle = SleepCycleStore.current(this)
        if (
            cycle.active &&
            DirectRadioStore.isPendingForCycle(this, cycle.cycleId)
        ) {
            val restored =
                RadioController.restoreDirect(this, cycle.cycleId)
            DiagnosticsStateStore.recordEvent(
                this,
                "Disable → PServer radios " +
                    if (restored) "restored" else "restore pending"
            )
            if (!restored) {
                SleepCycleStore.markRestoreProblem(
                    this,
                    "Direct Wi-Fi / Bluetooth restore is still pending."
                )
            }
            cycle = SleepCycleStore.current(this)
        }
        if (
            cycle.active &&
            cycle.helperExpected &&
            !cycle.helperSleepRequested &&
            !cycle.helperRestored
        ) {
            SleepCycleStore.markHelperRestored(this)
            cycle = SleepCycleStore.current(this)
        }

        val helperRestoreNeeded =
            cycle.active &&
                cycle.helperExpected &&
                cycle.helperSleepRequested &&
                !cycle.helperRestored
        val networkRestoreNeeded = hasPendingNetworkConnectorRestore()

        helperNetworkRestoreHandoffState.prepare(
            helperRestoreNeeded = helperRestoreNeeded,
            networkRestoreNeeded = networkRestoreNeeded
        )

        if (helperRestoreNeeded) {
            val sent = HelperController.restoreNow(this, cycle.cycleId)
            armHelperRestoreRetry(
                cycleId = cycle.cycleId,
                requestSent = sent
            )
            if (sent) {
                disableRestoreState.markInitializationComplete()
                Log.i(
                    TAG,
                    "Disable requested -> waiting for Helper restore result"
                )
                return
            }

            disableRestoreState.markInitializationComplete()
            helperNetworkRestoreHandoffState.clear()
            Log.w(TAG, "Disable requested -> Helper restore could not be sent")
            SleepCycleStore.markRestoreProblem(
                this,
                "Compatibility Helper is unavailable, so Wi-Fi / Bluetooth cannot be restored."
            )
            DiagnosticsStateStore.recordEvent(this, "Disable → Helper restore pending")
            finishDisableRestoreIfRequested(forceStop = true)
            return
        }

        if (networkRestoreNeeded) {
            disableRestoreState.markInitializationComplete()
            waitForNetworkAndRestorePendingConnectors()
            return
        }

        disableRestoreState.markInitializationComplete()
        SleepCycleStore.completeIfRestored(this)
        finishDisableRestoreIfRequested()
    }

    private fun finishDisableRestoreIfRequested(forceStop: Boolean = false) {
        if (!disableRestoreState.isRequested) return

        if (forceStop) {
            disableRestoreState.requestForceStop()
        }

        if (
            disableRestoreState.isInitializing ||
            basicSyncRestoreRetryState.isPending ||
            deviceControlRestoreRetryState.isPending
        ) {
            return
        }

        val shouldForceStop = disableRestoreState.forceStopRequested
        if (!shouldForceStop && SleepCycleStore.isActive(this)) {
            return
        }

        disableRestoreState.complete()
        helperNetworkRestoreHandoffState.clear()

        if (!shouldForceStop) {
            DiagnosticsStateStore.recordEvent(this, "SleepManager disabled")
        }

        stopSelf()
    }

    private fun onScreenOff() {
        resetBasicSyncWakeRestoreConfirmation()
        closedLidState.clearSleepRequestPending()

        if (sleepCycleRuntimeState.consumeFalseWakeResleepPending()) {
            behaviorHistory?.protectedResleep()
            handler.removeCallbacks(closedLidScreenOnRecheckRunnable)
            Log.i(
                TAG,
                "Screen OFF after suppressed closed-lid false wake -> preserving existing sleep transaction"
            )
            return
        }

        behaviorHistory?.standby()
        wakeTransitionSyncState.clear()

        if (
            SyncMaintenancePolicy.shouldCancelMaintenanceOnScreenOff(
                syncMaintenanceRunner?.activeTrigger
            )
        ) {
            cancelSyncMaintenance(restoreSleepWifi = false)
        }

        if (AppPreferences.advancedDiagnosticsEnabled(this)) {
            DiagnosticsCycleStore.begin(this)
            captureAdvancedDiagnostics(DiagnosticsCycleStore.PHASE_SLEEP_START)
        }
        BatterySleepStore.beginSession(this)
        cancelNetworkReadyWait()
        helperNetworkRestoreHandoffState.clear()
        handler.removeCallbacks(closedLidScreenOnRecheckRunnable)

        val existingCycle = SleepCycleStore.current(this)
        if (sleepCycleRuntimeState.actionsApplied || existingCycle.active) {
            sleepCycleRuntimeState.markActionsApplied()

            val helperSleepPending =
                existingCycle.active &&
                    existingCycle.helperExpected &&
                    !existingCycle.helperSleepRequested
            val directRadioSleepPending =
                existingCycle.active &&
                    !existingCycle.helperExpected &&
                    (existingCycle.wifiManaged || existingCycle.bluetoothManaged) &&
                    RadioController.backend(this) == RadioBackend.PSERVER &&
                    !DirectRadioStore.isPendingForCycle(
                        this,
                        existingCycle.cycleId
                    )
            val radioSleepPending =
                helperSleepPending || directRadioSleepPending
            val batterySaverWillEnable =
                batterySaverWillEnableForSleep()
            val syncthingStopPending =
                SleepCycleStore.hasConnectorChange(
                    this,
                    SyncthingConnector.id
                )
            val basicSyncStopPending =
                SleepCycleStore.hasConnectorChange(
                    this,
                    BasicSyncConnector.id
                )
            val tailscaleVerificationPending =
                isTailscaleSleepVerificationPending()

            if (
                RaOfflineProxySleepStore.isPendingForCycle(
                    this,
                    existingCycle.cycleId
                )
            ) {
                Log.i(
                    TAG,
                    "Recovered pending sleep transaction; resuming RAOfflineProxy gate"
                )
                raOfflineProxyCoordinator()
                    .resumeSleepGate(existingCycle.cycleId)
                return
            }

            val waitForManagedStops =
                SleepWakePolicy
                    .shouldWaitForManagedStopsBeforeDisruptiveSleepAction(
                        wifiManaged = existingCycle.wifiManaged,
                        radioControlAvailable = radioSleepPending,
                        batterySaverWillEnable = batterySaverWillEnable,
                        syncthingStopRequested = syncthingStopPending,
                        basicSyncStopRequested = basicSyncStopPending
                    )
            val waitForTailscale =
                SleepWakePolicy
                    .shouldWaitForTailscaleBeforeDisruptiveSleepAction(
                        radioControlAvailable = radioSleepPending,
                        batterySaverWillEnable = batterySaverWillEnable,
                        tailscaleVerificationPending =
                            tailscaleVerificationPending
                    )

            val postStopRecoveryNeeded =
                existingCycle.active &&
                    (
                        waitForManagedStops ||
                            waitForTailscale ||
                            radioSleepPending ||
                            batterySaverWillEnable
                    )

            if (postStopRecoveryNeeded) {
                val elapsed =
                    (System.currentTimeMillis() - existingCycle.startedAt)
                        .coerceAtLeast(0L)

                sleepStopWaitState.pendingPostStopActions = true
                sleepStopWaitState.pendingWifi =
                    radioSleepPending && existingCycle.wifiManaged
                sleepStopWaitState.pendingBluetooth =
                    radioSleepPending && existingCycle.bluetoothManaged
                sleepStopWaitState.pendingSyncthing =
                    waitForManagedStops && syncthingStopPending
                sleepStopWaitState.pendingBasicSync =
                    waitForManagedStops && basicSyncStopPending

                initializeSleepStopWait(elapsed)

                if (sleepStopWaitState.pendingBasicSync) {
                    BasicSyncController.requestStateBroadcast(this)
                }

                if (waitForTailscale) {
                    Log.i(
                        TAG,
                        "Recovered pending sleep transaction; resuming Tailscale verification before disruptive sleep actions"
                    )
                    scheduleTailscaleSleepVerification(resetAttempts = true)
                } else {
                    scheduleSleepRadioStopCheck(0L)
                    Log.i(
                        TAG,
                        "Recovered pending sleep transaction; resuming post-STOP sleep actions"
                    )
                }
                return
            }

            if (tailscaleVerificationPending) {
                Log.i(TAG, "Recovered pending Tailscale disconnect verification")
                scheduleTailscaleSleepVerification(resetAttempts = true)
                return
            }

            Log.i(
                TAG,
                "Screen OFF -> active sleep transaction already exists; skipping duplicate"
            )
            return
        }
        if (sleepDelayState.isPending) {
            Log.i(TAG, "Screen OFF -> sleep delay already pending")
            return
        }

        val sleepDelayMs = AppPreferences.effectiveSleepDelayMs(this)
        if (sleepDelayMs > 0L) {
            scheduleSleepDelay(sleepDelayMs)
            Log.i(TAG, "Screen OFF -> sleep delay scheduled for ${sleepDelayMs}ms")
            return
        }

        performFreshSleepActions()
    }

    private fun performFreshSleepActions() {
        sleepCycleRuntimeState.markActionsApplied()

        handler.removeCallbacks(sleepRadioRunnable)
        cancelActiveBasicSyncWait()
        releaseSleepTransitionWakeLock()

        val conditions = SleepConditionEvaluator.evaluate(this)
        if (!conditions.met) {
            SyncMaintenanceScheduler.cancel(this)
            SyncTransitionStore.clear(this)
            sleepCycleRuntimeState.markSkippedByConditions()
            val reason = conditions.failedReasons.joinToString(" · ")
            Log.i(TAG, "Sleep actions skipped -> $reason")
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep skipped → $reason"
            )
            return
        }

        sleepCycleRuntimeState.clearSkippedByConditions()

        val transitionSyncRequested = syncThenStopAvailable()
        if (
            !transitionSyncRequested &&
            shouldProbeBasicSyncBeforeNormalSleep()
        ) {
            probeBasicSyncBeforeNormalSleep()
            return
        }

        continueFreshSleepActions(transitionSyncRequested)
    }

    private fun shouldProbeBasicSyncBeforeNormalSleep(): Boolean =
        AppPreferences.manageBasicSync(this) &&
            BasicSyncController.isInstalled(this) &&
            BasicSyncController.supportsSyncCounters(this)

    private fun probeBasicSyncBeforeNormalSleep() {
        BasicSyncController.startStateObserver(this)
        val generation = ++basicSyncPreSleepState.freshProbeGeneration

        acquireSleepTransitionWakeLock(
            BASIC_SYNC_FRESH_STATE_TIMEOUT_MS +
                SLEEP_TRANSITION_WAKELOCK_TIMEOUT_MS
        )

        val scheduled = runCatching {
            syncStopProbeExecutor.execute {
                val state =
                    runCatching {
                        BasicSyncController.requestState(
                            this,
                            BASIC_SYNC_FRESH_STATE_TIMEOUT_MS
                        )
                    }.getOrElse { error ->
                        Log.e(TAG, "BasicSync fresh state probe failed before sleep", error)
                        null
                    }

                handler.post {
                    if (generation != basicSyncPreSleepState.freshProbeGeneration) {
                        return@post
                    }

                    if (
                        !isEffectivelySleepingNow() ||
                        !AppPreferences.isEnabled(this) ||
                        !sleepCycleRuntimeState.actionsApplied
                    ) {
                        releaseSleepTransitionWakeLock()
                        return@post
                    }

                    if (
                        basicSyncCompletionState(state) ==
                        SyncCompletionState.SYNCING
                    ) {
                        startActiveBasicSyncWait()
                    } else {
                        releaseSleepTransitionWakeLock()
                        continueFreshSleepActions(
                            transitionSyncRequested = false
                        )
                    }
                }
            }
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to schedule BasicSync fresh state probe", error)
            false
        }

        if (!scheduled) {
            releaseSleepTransitionWakeLock()
            continueFreshSleepActions(
                transitionSyncRequested = false
            )
        }
    }

    private fun startActiveBasicSyncWait() {
        basicSyncPreSleepState.beginActiveWait(SystemClock.elapsedRealtime())
        acquireSleepTransitionWakeLock(
            BASIC_SYNC_ACTIVE_FINISH_TIMEOUT_MS +
                SLEEP_TRANSITION_WAKELOCK_TIMEOUT_MS
        )
        BasicSyncController.requestStateBroadcast(this)
        handler.post(activeBasicSyncFinishRunnable)
        Log.i(
            TAG,
            "BasicSync is already syncing; waiting for it to finish before sleep actions"
        )
        DiagnosticsStateStore.recordEvent(
            this,
            "Sleep → waiting for active BasicSync sync to finish"
        )
    }

    private fun pollActiveBasicSyncBeforeSleep() {
        if (!basicSyncPreSleepState.waitingForActiveSync) return

        if (
            isRealWakeNow() ||
            !AppPreferences.isEnabled(this)
        ) {
            cancelActiveBasicSyncWait()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - basicSyncPreSleepState.waitStartedAtElapsed
        val completion =
            basicSyncCompletionState(
                BasicSyncController.lastObservedState()
            )

        when (completion) {
            SyncCompletionState.SYNCING -> {
                basicSyncPreSleepState.syncedSinceElapsed = 0L
            }

            SyncCompletionState.SYNCED -> {
                if (basicSyncPreSleepState.syncedSinceElapsed == 0L) {
                    basicSyncPreSleepState.syncedSinceElapsed = now
                } else if (
                    now - basicSyncPreSleepState.syncedSinceElapsed >=
                    BASIC_SYNC_IDLE_STABILITY_MS
                ) {
                    cancelActiveBasicSyncWait()
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Sleep → active BasicSync sync finished"
                    )
                    continueFreshSleepActions(
                        transitionSyncRequested = false
                    )
                    return
                }
            }

            SyncCompletionState.UNKNOWN -> {
                basicSyncPreSleepState.syncedSinceElapsed = 0L
            }
        }

        if (elapsed >= BASIC_SYNC_ACTIVE_FINISH_TIMEOUT_MS) {
            cancelActiveBasicSyncWait()
            Log.w(
                TAG,
                "Timed out waiting for active BasicSync sync; continuing with STOP"
            )
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → BasicSync active sync wait timed out"
            )
            continueFreshSleepActions(
                transitionSyncRequested = false
            )
            return
        }

        BasicSyncController.requestStateBroadcast(this)
        handler.postDelayed(
            activeBasicSyncFinishRunnable,
            BASIC_SYNC_ACTIVE_FINISH_POLL_MS
        )
    }

    private fun cancelActiveBasicSyncWait() {
        handler.removeCallbacks(activeBasicSyncFinishRunnable)
        basicSyncPreSleepState.cancelActiveWait()
    }

    private fun continueFreshSleepActions(
        transitionSyncRequested: Boolean
    ) {
        val transitionOwnershipReady =
            !transitionSyncRequested ||
                !AppPreferences.manageBasicSync(this) ||
                SyncStopOwnershipStore.captureBasicSyncIfNeeded(this)
        val transitionSyncAvailable =
            transitionSyncRequested && transitionOwnershipReady

        if (transitionSyncRequested && !transitionOwnershipReady) {
            Log.w(
                TAG,
                "Sync then stop skipped for this sleep: BasicSync original state is not known yet"
            )
        }

        if (!transitionSyncAvailable) {
            SyncTransitionStore.clear(this)
        }

        if (transitionSyncAvailable) {
            val started =
                syncRunner().start(
                    SyncMaintenanceTrigger.BEFORE_SLEEP
                ) { snapshot ->
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Pre-sleep sync → ${snapshot.outcome}"
                    )

                    val stillSleeping = isEffectivelySleepingNow()
                    val shouldContinueSleep =
                        SyncMaintenancePolicy
                            .shouldContinueSleepAfterPreSleepMaintenance(
                                stillSleeping = stillSleeping,
                                managerEnabled = AppPreferences.isEnabled(this),
                                actionsApplied =
                                    sleepCycleRuntimeState.actionsApplied
                            )

                    if (shouldContinueSleep) {
                        // A wake sync is earned only once pre-sleep maintenance
                        // finished while this sleep transition is still valid.
                        SyncTransitionStore.armWakeSync(this)
                        prepareSyncthingAndApplyFreshSleepActions(
                            keepBasicSyncStopped = true
                        )
                    } else {
                        SyncTransitionStore.clear(this)
                        Log.i(
                            TAG,
                            "Pre-sleep sync finished after wake; sleep actions not continued"
                        )
                    }
                }

            if (started) {
                Log.i(TAG, "Pre-sleep sync maintenance started")
                return
            }
        }

        prepareSyncthingAndApplyFreshSleepActions(
            keepBasicSyncStopped = transitionSyncAvailable
        )
    }

    private fun prepareSyncthingAndApplyFreshSleepActions(
        keepBasicSyncStopped: Boolean
    ) {
        if (!AppPreferences.manageSyncthing(this)) {
            applyFreshSleepActions(
                keepBasicSyncStopped = keepBasicSyncStopped,
                syncthingPreSleepProbe = null
            )
            return
        }

        val target = SyncthingController.selectedTarget(this)
        if (target == null) {
            applyFreshSleepActions(
                keepBasicSyncStopped = keepBasicSyncStopped,
                syncthingPreSleepProbe = null
            )
            return
        }

        val generation = syncthingPreSleepState.nextProbeGeneration()
        val packageName = target.packageName

        acquireSleepTransitionWakeLock(
            SYNCTHING_PRE_SLEEP_PROBE_TIMEOUT_MS +
                SLEEP_TRANSITION_WAKELOCK_TIMEOUT_MS
        )

        val scheduled =
            runCatching {
                syncStopProbeExecutor.execute {
                    val probe =
                        runCatching {
                            SyncthingConnector.probeBeforeSleep(packageName)
                        }.getOrElse { error ->
                            Log.w(
                                TAG,
                                "Syncthing pre-sleep probe failed for $packageName",
                                error
                            )
                            SyncthingConnector.PreSleepProbe(
                                packageName = packageName,
                                state = SyncthingConnector.PreSleepState.UNKNOWN
                            )
                        }

                    handler.post {
                        if (!syncthingPreSleepState.isCurrent(generation)) {
                            return@post
                        }

                        if (
                            !isEffectivelySleepingNow() ||
                            !AppPreferences.isEnabled(this) ||
                            !sleepCycleRuntimeState.actionsApplied
                        ) {
                            releaseSleepTransitionWakeLock()
                            return@post
                        }

                        releaseSleepTransitionWakeLock()
                        applyFreshSleepActions(
                            keepBasicSyncStopped = keepBasicSyncStopped,
                            syncthingPreSleepProbe = probe
                        )
                    }
                }
                true
            }.getOrElse { error ->
                Log.e(TAG, "Unable to schedule Syncthing pre-sleep probe", error)
                false
            }

        if (!scheduled) {
            releaseSleepTransitionWakeLock()
            applyFreshSleepActions(
                keepBasicSyncStopped = keepBasicSyncStopped,
                syncthingPreSleepProbe =
                    SyncthingConnector.PreSleepProbe(
                        packageName = packageName,
                        state = SyncthingConnector.PreSleepState.UNKNOWN
                    )
            )
        }
    }

    private fun applyFreshSleepActions(
        keepBasicSyncStopped: Boolean,
        syncthingPreSleepProbe: SyncthingConnector.PreSleepProbe?
    ) {
        val requestedWifi = AppPreferences.manageWifi(this)
        val requestedBluetooth = AppPreferences.manageBluetooth(this)
        val wifi =
            requestedWifi && RadioController.canManageWifi(this)
        val bluetooth =
            requestedBluetooth && RadioController.canManageBluetooth(this)
        val syncthing = AppPreferences.manageSyncthing(this)
        val tailscale =
            AppPreferences.manageTailscale(this) &&
                TailscaleConnector.isInstalled(this)
        val jamesDsp =
            AppPreferences.manageJamesDsp(this) &&
                JamesDspConnector.isInstalled(this)
        val basicSync =
            AppPreferences.manageBasicSync(this) &&
                BasicSyncConnector.isInstalled(this)
        if (basicSync) {
            BasicSyncController.startStateObserver(this)
        }
        val radiosManaged = wifi || bluetooth
        val radioBackend =
            if (radiosManaged) RadioController.backend(this)
            else RadioBackend.NONE
        val radioControlAvailable =
            radiosManaged && radioBackend != RadioBackend.NONE
        val helperAvailable =
            radiosManaged && radioBackend == RadioBackend.HELPER
        val batterySaverWillEnable =
            batterySaverWillEnableForSleep()

        val cycle = SleepCycleStore.begin(
            context = this,
            helperExpected = helperAvailable,
            wifiManaged = wifi,
            bluetoothManaged = bluetooth
        )
        Log.i(
            TAG,
            "Screen OFF -> cycle=${cycle.cycleId} wifi=$wifi/$requestedWifi " +
                "bluetooth=$bluetooth/$requestedBluetooth " +
                "syncthing=$syncthing tailscale=$tailscale jamesDsp=$jamesDsp " +
                "basicSync=$basicSync keepBasicSyncStopped=$keepBasicSyncStopped " +
                "radioBackend=$radioBackend"
        )

        // Syncthing always keeps its normal STOP/FOLLOW ownership. Advanced
        // completion-aware maintenance is BasicSync-only.
        val syncthingResult =
            if (syncthing) {
                if (syncthingPreSleepProbe != null) {
                    SyncthingConnector.sleep(
                        context = this,
                        preSleepProbe = syncthingPreSleepProbe
                    )
                } else {
                    SyncthingConnector.sleep(this)
                }
            } else {
                null
            }

        syncthingSleepSummaryState =
            when {
                !syncthing ->
                    SyncthingSleepSummaryState.NOT_MANAGED
                syncthingResult?.changed != true ->
                    SyncthingSleepSummaryState.STOP_NOT_SENT
                syncthingPreSleepProbe?.state ==
                    SyncthingConnector.PreSleepState.CONFIRMED_RUNNING ->
                    SyncthingSleepSummaryState.STOP_SENT
                else ->
                    SyncthingSleepSummaryState.STOP_UNVERIFIED
            }

        if (syncthingResult?.changed == true) {
            SleepCycleStore.recordConnectorChange(
                this,
                SyncthingConnector.id,
                syncthingResult.restoreToken
            )
        }

        val tailscaleResult = if (tailscale) {
            TailscaleConnector.sleep(this)
        } else {
            null
        }

        if (
            tailscaleResult?.attempted == true &&
            tailscaleResult.restoreToken == TailscaleConnector.TOKEN_VERIFY_DISCONNECT
        ) {
            SleepCycleStore.recordConnectorChange(
                this,
                TailscaleConnector.id,
                TailscaleConnector.TOKEN_VERIFY_DISCONNECT
            )
        }

        val jamesDspResult = if (jamesDsp) {
            JamesDspConnector.sleep(this)
        } else {
            null
        }

        if (jamesDsp) {
            DiagnosticsStateStore.recordEvent(
                this,
                when {
                    jamesDspResult?.changed == true ->
                        "Sleep → JamesDSP OFF sent · previous state unknown"
                    jamesDspResult?.attempted == true ->
                        "Sleep → JamesDSP OFF not confirmed"
                    else ->
                        "Sleep → JamesDSP unchanged · " +
                            (jamesDspResult?.detail ?: "no action")
                }
            )
        }

        if (jamesDspResult?.changed == true) {
            SleepCycleStore.recordConnectorChange(
                this,
                JamesDspConnector.id,
                jamesDspResult.restoreToken
            )
        }

        val basicSyncStopSent =
            if (basicSync && keepBasicSyncStopped) {
                BasicSyncController.sendStop(this)
            } else {
                false
            }

        val basicSyncResult =
            if (basicSync && !keepBasicSyncStopped) {
                BasicSyncConnector.sleep(this)
            } else {
                null
            }

        if (basicSyncResult?.changed == true) {
            SleepCycleStore.recordConnectorChange(
                this,
                BasicSyncConnector.id,
                basicSyncResult.restoreToken
            )
            val restoreTarget =
                BasicSyncConnector.restoreTargetName(
                    basicSyncResult.restoreToken
                )
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → BasicSync STOP sent · restore $restoreTarget"
            )
            Log.i(
                TAG,
                "BasicSync STOP sent; restore target=$restoreTarget"
            )
        } else if (basicSyncResult?.detail != null) {
            Log.i(TAG, "BasicSync unchanged: ${basicSyncResult.detail}")
        }

        if (basicSync && keepBasicSyncStopped) {
            SleepCycleStore.clearConnectorChange(
                this,
                BasicSyncConnector.id
            )
            Log.i(
                TAG,
                "BasicSync STOP sent=$basicSyncStopSent; advanced sync ownership retained"
            )
        }

        val syncthingStopRequested = syncthingResult?.changed == true
        val basicSyncStopRequested =
            if (keepBasicSyncStopped) {
                basicSyncStopSent
            } else {
                basicSyncResult?.changed == true
            }
        val tailscaleVerificationPending = isTailscaleSleepVerificationPending()
        val waitForManagedStops =
            SleepWakePolicy
                .shouldWaitForManagedStopsBeforeDisruptiveSleepAction(
                    wifiManaged = wifi,
                    radioControlAvailable = radioControlAvailable,
                    batterySaverWillEnable = batterySaverWillEnable,
                    syncthingStopRequested = syncthingStopRequested,
                    basicSyncStopRequested = basicSyncStopRequested
                )
        val waitForTailscale =
            SleepWakePolicy
                .shouldWaitForTailscaleBeforeDisruptiveSleepAction(
                    radioControlAvailable = radioControlAvailable,
                    batterySaverWillEnable = batterySaverWillEnable,
                    tailscaleVerificationPending =
                        tailscaleVerificationPending
                )

        if (syncthing) {
            when {
                !syncthingStopRequested ->
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Sleep → Syncthing STOP not sent"
                    )
                !waitForManagedStops ->
                    DiagnosticsStateStore.recordEvent(
                        this,
                        when (syncthingSleepSummaryState) {
                            SyncthingSleepSummaryState.STOP_UNVERIFIED ->
                                "Sleep → Syncthing STOP sent · state unverified"
                            else ->
                                "Sleep → Syncthing STOP sent"
                        }
                    )
            }
        }

        if (waitForManagedStops || waitForTailscale) {
            sleepStopWaitState.pendingPostStopActions = true
            sleepStopWaitState.pendingWifi = wifi
            sleepStopWaitState.pendingBluetooth = bluetooth
            sleepStopWaitState.pendingSyncthing =
                waitForManagedStops && syncthingStopRequested
            sleepStopWaitState.pendingBasicSync =
                waitForManagedStops && basicSyncStopRequested
            initializeSleepStopWait()

            if (sleepStopWaitState.pendingBasicSync) {
                BasicSyncController.requestStateBroadcast(this)
            }

            if (waitForTailscale) {
                Log.i(
                    TAG,
                    "Waiting for Tailscale disconnect verification before disruptive sleep actions"
                )
                scheduleTailscaleSleepVerification(resetAttempts = true)
            } else {
                scheduleSleepRadioStopCheck()
            }
        } else {
            if (tailscaleVerificationPending) {
                scheduleTailscaleSleepVerification(resetAttempts = true)
            }

            applySleepConnectivityAfterRaOfflineProxyGate(
                wifi = wifi,
                bluetooth = bluetooth
            )
        }

        if (
            SleepWakePolicy.shouldCompleteSleepCycleAfterFreshActions(
                postStopActionsPending =
                    sleepStopWaitState.pendingPostStopActions
            )
        ) {
            SleepCycleStore.completeIfRestored(this)
        }

        SyncMaintenanceScheduler.scheduleNext(this)
    }

    private fun syncThenStopAvailable(): Boolean =
        AppPreferences.syncThenStopOnSleepWake(this) &&
            ManagedSyncProviders.completionReady(this)

    private fun batterySaverWillEnableForSleep(): Boolean {
        val manageEnabled = AppPreferences.manageBatterySaver(this)
        if (!manageEnabled) return false

        val controlSupported =
            DeviceControlController.supportsBatterySaverControl(this)
        if (!controlSupported) return false

        val owned = DeviceControlStore.batterySaver(this)
        if (owned.owned) return false

        return BatterySaverPolicy.shouldEnableForSleep(
            manageEnabled = manageEnabled,
            controlSupported = controlSupported,
            owned = false,
            currentlyEnabled =
                DeviceControlController.batterySaverEnabled(this),
            externalPowerConnected =
                DeviceControlController.externalPowerConnected(this)
        )
    }

    private fun initializeSleepStopWait(
        elapsedBeforeRecoveryMs: Long = 0L
    ) {
        sleepStopWaitState.begin(
            nowElapsed = SystemClock.elapsedRealtime(),
            elapsedBeforeRecoveryMs = elapsedBeforeRecoveryMs,
            timeoutMs = SYNC_STOP_TIMEOUT_MS
        )

        acquireSleepTransitionWakeLock(
            SYNC_STOP_TIMEOUT_MS +
                SLEEP_TRANSITION_WAKELOCK_TIMEOUT_MS
        )

        Log.i(
            TAG,
            "Waiting before disruptive sleep actions: Syncthing=${sleepStopWaitState.pendingSyncthing} " +
                "BasicSync=${sleepStopWaitState.pendingBasicSync} timeout=${SYNC_STOP_TIMEOUT_MS}ms"
        )
    }

    private fun scheduleSleepRadioStopCheck(
        delayMs: Long = SYNC_STOP_POLL_INTERVAL_MS
    ) {
        handler.removeCallbacks(sleepRadioRunnable)
        handler.postDelayed(
            sleepRadioRunnable,
            delayMs.coerceAtLeast(0L)
        )
    }

    private fun continueSleepRadioAfterSyncStop() {
        if (!sleepStopWaitState.pendingPostStopActions) {
            releaseSleepTransitionWakeLock()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (sleepStopWaitState.startedAtElapsed == 0L) {
            sleepStopWaitState.startedAtElapsed = now
        }
        val elapsed = now - sleepStopWaitState.startedAtElapsed
        val timedOut = elapsed >= SYNC_STOP_TIMEOUT_MS

        val basicSyncStopped =
            when {
                !sleepStopWaitState.pendingBasicSync -> true
                BasicSyncController.supportsStateApi(this) ->
                    BasicSyncController.isConfirmedStopped()
                else ->
                    // BasicSync versions without the state API cannot confirm
                    // STOP. Preserve the old short grace rather than blocking
                    // the sleep transition for the full timeout.
                    elapsed >= SYNCTHING_STOP_GRACE_MS
            }

        if (
            sleepStopWaitState.pendingBasicSync &&
            !basicSyncStopped &&
            !timedOut &&
            now - sleepStopWaitState.lastBasicSyncStateRequestAtElapsed >= 1_000L
        ) {
            sleepStopWaitState.lastBasicSyncStateRequestAtElapsed = now
            BasicSyncController.requestStateBroadcast(this)
        }

        if (
            sleepStopWaitState.pendingSyncthing &&
            !sleepStopWaitState.syncthingConfirmed &&
            !timedOut
        ) {
            if (elapsed < SYNCTHING_STOP_GRACE_MS) {
                scheduleSleepRadioStopCheck(
                    (SYNCTHING_STOP_GRACE_MS - elapsed)
                        .coerceAtMost(SYNC_STOP_POLL_INTERVAL_MS)
                )
                return
            }

            if (!sleepStopWaitState.syncthingProbeInFlight) {
                sleepStopWaitState.syncthingProbeInFlight = true
                val generation = sleepStopWaitState.generation
                val restoreToken =
                    SleepCycleStore.connectorChange(
                        this,
                        SyncthingConnector.id
                    )?.restoreToken

                syncStopProbeExecutor.execute {
                    val verification =
                        runCatching {
                            SyncthingConnector.verifyStopAfterGrace(
                                restoreToken
                            )
                        }.getOrElse { error ->
                            Log.w(TAG, "Syncthing STOP verification failed", error)
                            null
                        }

                    handler.post {
                        if (generation != sleepStopWaitState.generation) {
                            return@post
                        }

                        sleepStopWaitState.syncthingProbeInFlight = false
                        if (
                            sleepStopWaitState.pendingSyncthing &&
                            SleepCycleStore.isActive(this)
                        ) {
                            val elapsedNow =
                                SystemClock.elapsedRealtime() -
                                    sleepStopWaitState.startedAtElapsed

                            when (verification) {
                                true -> {
                                    sleepStopWaitState.syncthingConfirmed = true
                                    syncthingSleepSummaryState =
                                        SyncthingSleepSummaryState.STOP_CONFIRMED
                                    Log.i(
                                        TAG,
                                        "Syncthing STOP confirmed before disruptive sleep actions"
                                    )
                                    DiagnosticsStateStore.recordEvent(
                                        this@SleepManagerService,
                                        "Sleep → Syncthing STOP confirmed"
                                    )
                                }

                                false -> {
                                    Log.i(
                                        TAG,
                                        "Syncthing still running; delaying disruptive sleep actions while STOP completes"
                                    )
                                }

                                null -> {
                                    if (
                                        elapsedNow >=
                                        SYNCTHING_UNVERIFIED_STOP_GRACE_MS
                                    ) {
                                        // No supported state API exists for this
                                        // target. Keep a conservative fixed grace
                                        // instead of treating an unreachable local
                                        // health endpoint as proof that STOP finished.
                                        sleepStopWaitState.syncthingConfirmed = true
                                        syncthingSleepSummaryState =
                                            SyncthingSleepSummaryState.STOP_UNVERIFIED
                                        Log.i(
                                            TAG,
                                            "Syncthing STOP state unavailable; fallback grace elapsed before disruptive sleep actions"
                                        )
                                        DiagnosticsStateStore.recordEvent(
                                            this@SleepManagerService,
                                            "Sleep → Syncthing STOP sent · state unverified"
                                        )
                                    }
                                }
                            }

                            val nextDelay =
                                if (
                                    verification == null &&
                                    !sleepStopWaitState.syncthingConfirmed
                                ) {
                                    (
                                        SYNCTHING_UNVERIFIED_STOP_GRACE_MS -
                                            elapsedNow
                                    ).coerceAtLeast(0L)
                                } else {
                                    0L
                                }

                            scheduleSleepRadioStopCheck(nextDelay)
                        }
                    }
                }
            }
            return
        }

        if (
            !timedOut &&
            (!basicSyncStopped ||
                (sleepStopWaitState.pendingSyncthing && !sleepStopWaitState.syncthingConfirmed))
        ) {
            scheduleSleepRadioStopCheck()
            return
        }

        if (sleepStopWaitState.pendingBasicSync && !basicSyncStopped) {
            Log.w(
                TAG,
                "BasicSync STOP not confirmed before disruptive sleep actions timeout"
            )
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → BasicSync STOP not confirmed before disruptive sleep actions"
            )
        }

        if (sleepStopWaitState.pendingSyncthing && !sleepStopWaitState.syncthingConfirmed) {
            syncthingSleepSummaryState =
                SyncthingSleepSummaryState.STOP_NOT_CONFIRMED
            if (
                SleepCycleStore.hasConnectorChange(
                    this,
                    SyncthingConnector.id
                )
            ) {
                SleepCycleStore.clearConnectorChange(
                    this,
                    SyncthingConnector.id
                )
            }
            Log.w(
                TAG,
                "Syncthing STOP not confirmed before disruptive sleep actions timeout"
            )
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → Syncthing STOP not confirmed before disruptive sleep actions"
            )
        }

        val wifi = sleepStopWaitState.pendingWifi
        val bluetooth = sleepStopWaitState.pendingBluetooth

        clearPendingSleepStopWait()

        Log.i(
            TAG,
            "Sync STOP gate complete -> applying sleep device controls and radios"
        )
        applySleepConnectivityAfterRaOfflineProxyGate(
            wifi = wifi,
            bluetooth = bluetooth
        )
    }

    private fun clearPendingSleepStopWait() {
        handler.removeCallbacks(sleepRadioRunnable)
        sleepStopWaitState.clear()
    }

    private fun applySleepConnectivityAfterRaOfflineProxyGate(
        wifi: Boolean,
        bluetooth: Boolean
    ) {
        if (!AppPreferences.manageRaOfflineProxy(this)) {
            applySleepConnectivity(wifi, bluetooth)
            return
        }

        if (!RaOfflineProxyController.isInstalled(this)) {
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → RAOfflineProxy not installed · gate skipped"
            )
            applySleepConnectivity(wifi, bluetooth)
            return
        }

        val availability =
            RaOfflineProxyConnector.availability(this)
        if (
            availability !is
            com.med.sleepmanager.integration.connector.ConnectorAvailability.Available
        ) {
            val reason =
                (
                    availability as?
                        com.med.sleepmanager.integration.connector.ConnectorAvailability.Unavailable
                    )?.reason ?: "unavailable"
            Log.w(
                TAG,
                "RAOfflineProxy integration unavailable: " + reason
            )
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → RAOfflineProxy unavailable · " + reason
            )

            // Never stop RAOfflineProxy when SleepManager cannot safely
            // restore it. Preserve managed Wi-Fi for this sleep instead.
            applySleepConnectivity(
                wifi = false,
                bluetooth = bluetooth
            )
            return
        }

        val cycle = SleepCycleStore.current(this)
        if (!cycle.active) {
            Log.w(
                TAG,
                "RAOfflineProxy gate skipped: no active sleep cycle"
            )
            applySleepConnectivity(wifi, bluetooth)
            return
        }

        raOfflineProxyCoordinator().beginSleepGate(
            cycleId = cycle.cycleId,
            wifi = wifi,
            bluetooth = bluetooth
        )
    }

    private fun applySleepConnectivity(
        wifi: Boolean,
        bluetooth: Boolean
    ) {
        applyBatterySaverForSleep()

        val cycle = SleepCycleStore.current(this)
        if (!(wifi || bluetooth) || !cycle.active) {
            releaseSleepTransitionWakeLock()
            DiagnosticsStateStore.recordEvent(
                this,
                buildSleepSummary(
                    wifiManaged = false,
                    wifiChanged = false,
                    bluetoothManaged = false,
                    bluetoothChanged = false
                )
            )
            SleepCycleStore.completeIfRestored(this)
            return
        }

        when (RadioController.backend(this)) {
            RadioBackend.PSERVER -> {
                val result =
                    RadioController.applyDirectSleep(
                        context = this,
                        wifi = wifi,
                        bluetooth = bluetooth,
                        cycleId = cycle.cycleId
                    )
                releaseSleepTransitionWakeLock()
                DiagnosticsStateStore.recordEvent(
                    this,
                    buildSleepSummary(
                        wifiManaged = result.wifiManaged,
                        wifiChanged = result.wifiChanged,
                        bluetoothManaged = result.bluetoothManaged,
                        bluetoothChanged = result.bluetoothChanged
                    ) + " · radio=PServer" +
                        if (result.success) "" else " · radioError"
                )
                SleepCycleStore.completeIfRestored(this)
            }

            RadioBackend.HELPER -> {
                val helperSent =
                    HelperController.sendSleep(
                        this,
                        wifi,
                        bluetooth,
                        cycle.cycleId
                    )
                if (helperSent) {
                    SleepCycleStore.markHelperSleepRequested(this)
                } else {
                    if (cycle.helperExpected) {
                        SleepCycleStore.markRestoreProblem(
                            this,
                            "Compatibility Helper could not receive the sleep radio request."
                        )
                    }
                    releaseSleepTransitionWakeLock()
                }
            }

            RadioBackend.NONE -> {
                if (cycle.helperExpected) {
                    SleepCycleStore.markHelperRestored(this)
                }
                releaseSleepTransitionWakeLock()
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Sleep → radio control unavailable; Wi-Fi / Bluetooth left unchanged"
                )
                SleepCycleStore.completeIfRestored(this)
            }
        }
    }

    private fun applyBatterySaverForSleep() {
        val manageEnabled = AppPreferences.manageBatterySaver(this)
        if (!manageEnabled) {
            DeviceControlStore.setBatterySaverDeferredForExternalPower(
                this,
                deferred = false
            )
            return
        }

        val controlSupported =
            DeviceControlController.supportsBatterySaverControl(this)
        if (!controlSupported) {
            DeviceControlStore.setBatterySaverDeferredForExternalPower(
                this,
                deferred = false
            )
            return
        }

        val owned = DeviceControlStore.batterySaver(this)
        if (owned.owned) return

        val previous = DeviceControlController.batterySaverEnabled(this)
        val externalPowerConnected =
            DeviceControlController.externalPowerConnected(this)
        if (
            !BatterySaverPolicy.shouldEnableForSleep(
                manageEnabled = manageEnabled,
                controlSupported = controlSupported,
                owned = false,
                currentlyEnabled = previous,
                externalPowerConnected = externalPowerConnected
            )
        ) {
            if (externalPowerConnected && !previous) {
                DeviceControlStore.setBatterySaverDeferredForExternalPower(
                    this,
                    deferred = true
                )
                Log.i(
                    TAG,
                    "Battery Saver skipped for sleep: external power connected"
                )
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Sleep → Battery Saver deferred · external power"
                )
            } else {
                DeviceControlStore.setBatterySaverDeferredForExternalPower(
                    this,
                    deferred = false
                )
            }
            return
        }

        DeviceControlStore.setBatterySaverDeferredForExternalPower(
            this,
            deferred = false
        )

        // Persist ownership before the privileged call. If the process dies
        // immediately after the toggle, wake recovery still knows what to undo.
        DeviceControlStore.takeBatterySaverOwnership(
            this,
            previous = false
        )

        if (DeviceControlController.setBatterySaverEnabled(true)) {
            Log.i(TAG, "Battery Saver enabled for sleep")
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → Battery Saver enabled"
            )
        } else {
            DeviceControlStore.clearBatterySaverOwnership(this)
            Log.w(TAG, "Unable to enable Battery Saver for sleep")
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → Battery Saver enable failed"
            )
        }
    }

    private fun handleExternalPowerChanged(connected: Boolean) {
        val effectivelySleeping = isEffectivelySleepingNow()
        val manageEnabled = AppPreferences.manageBatterySaver(this)
        val controlSupported =
            DeviceControlController.supportsBatterySaverControl(this)
        val deferred =
            DeviceControlStore.batterySaverDeferredForExternalPower(this)
        val owned = DeviceControlStore.batterySaver(this)
        val current = DeviceControlController.batterySaverEnabled(this)

        if (!effectivelySleeping) {
            DeviceControlStore.setBatterySaverDeferredForExternalPower(
                this,
                deferred = false
            )
            return
        }

        when (
            BatterySaverPolicy.powerEventDecision(
                manageEnabled = manageEnabled,
                controlSupported = controlSupported,
                effectivelySleeping = true,
                externalPowerConnected = connected,
                deferredForExternalPower = deferred,
                owned = owned.owned,
                previous = owned.previous,
                currentlyEnabled = current
            )
        ) {
            BatterySaverPowerEventDecision.NOTHING -> {
                if (!manageEnabled && deferred) {
                    DeviceControlStore.setBatterySaverDeferredForExternalPower(
                        this,
                        deferred = false
                    )
                }
            }

            BatterySaverPowerEventDecision.ENABLE_SLEEP_STATE -> {
                DeviceControlStore.takeBatterySaverOwnership(
                    this,
                    previous = current
                )
                if (DeviceControlController.setBatterySaverEnabled(true)) {
                    DeviceControlStore.setBatterySaverDeferredForExternalPower(
                        this,
                        deferred = false
                    )
                    Log.i(
                        TAG,
                        "External power disconnected during sleep -> Battery Saver enabled"
                    )
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Sleep power disconnected → Battery Saver enabled"
                    )
                } else {
                    DeviceControlStore.clearBatterySaverOwnership(this)
                    Log.w(
                        TAG,
                        "Unable to enable Battery Saver after external power disconnect"
                    )
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Sleep power disconnected → Battery Saver enable failed"
                    )
                }
            }

            BatterySaverPowerEventDecision.RESTORE_PREVIOUS -> {
                val restored =
                    restoreOwnedBatterySaver("External power connected")
                if (
                    restored &&
                    manageEnabled &&
                    owned.owned &&
                    !owned.previous
                ) {
                    DeviceControlStore.setBatterySaverDeferredForExternalPower(
                        this,
                        deferred = true
                    )
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Sleep power connected → Battery Saver deferred"
                    )
                }
            }
        }
    }

    private fun restoreOwnedBatterySaver(reason: String): Boolean {
        val owned = DeviceControlStore.batterySaver(this)
        if (!owned.owned) return true

        val current = DeviceControlController.batterySaverEnabled(this)
        when (
            BatterySaverPolicy.restoreDecision(
                owned = true,
                previous = owned.previous,
                current = current
            )
        ) {
            BatterySaverRestoreDecision.NOTHING_TO_RESTORE -> return true

            BatterySaverRestoreDecision.CLEAR_OWNERSHIP -> {
                DeviceControlStore.clearBatterySaverOwnership(this)
                return true
            }

            BatterySaverRestoreDecision.RESTORE_PREVIOUS -> Unit
        }

        val restored =
            DeviceControlController.setBatterySaverEnabled(
                owned.previous
            )
        if (restored) {
            DeviceControlStore.clearBatterySaverOwnership(this)
            Log.i(TAG, "Battery Saver restored after $reason")
            DiagnosticsStateStore.recordEvent(
                this,
                "$reason → Battery Saver restored"
            )
        } else {
            Log.w(TAG, "Battery Saver restore failed after $reason")
            SleepCycleStore.markRestoreProblem(
                this,
                "Battery Saver restore is still pending."
            )
            DiagnosticsStateStore.recordEvent(
                this,
                "$reason → Battery Saver restore failed"
            )
        }
        return restored
    }

    private fun temporarilyRestoreBatterySaverForMaintenance(): Boolean {
        val owned = DeviceControlStore.batterySaver(this)
        if (!owned.owned || owned.previous) return false

        val current = DeviceControlController.batterySaverEnabled(this)
        if (
            !BatterySaverPolicy.shouldTemporarilyRestoreForMaintenance(
                owned = true,
                previous = false,
                current = current
            )
        ) {
            return false
        }

        val restored =
            DeviceControlController.setBatterySaverEnabled(false)
        if (restored) {
            Log.i(TAG, "Battery Saver temporarily restored for periodic sync")
        }
        return restored
    }

    private fun reapplyBatterySaverAfterMaintenance() {
        val owned = DeviceControlStore.batterySaver(this)
        if (!owned.owned || owned.previous) return

        val isRealWake = isRealWakeNow()
        if (isRealWake) return

        val current = DeviceControlController.batterySaverEnabled(this)
        if (
            !BatterySaverPolicy.shouldReapplyAfterMaintenance(
                owned = true,
                previous = false,
                isRealWake = false,
                current = current
            )
        ) {
            return
        }

        if (DeviceControlController.setBatterySaverEnabled(true)) {
            Log.i(TAG, "Battery Saver re-enabled after periodic sync")
        } else {
            Log.w(TAG, "Unable to re-enable Battery Saver after periodic sync")
        }
    }

    private fun shouldMonitorLid(): Boolean {
        val lidSupported = LidMonitor.isSupported()
        if (!lidSupported) return false

        val closedLidProtectionEnabled =
            AppPreferences.manageClosedLidProtection(this)
        if (closedLidProtectionEnabled) return true

        val chargingSeparationWithLidEnabled =
            AppPreferences.manageChargingSeparationWithLid(this)
        val chargingSeparationControlSupported =
            chargingSeparationWithLidEnabled &&
                DeviceControlController.supportsChargingSeparationControl(this)

        return ClamshellMonitoringPolicy.shouldMonitorLid(
            lidSupported = true,
            closedLidProtectionEnabled = false,
            chargingSeparationWithLidEnabled =
                chargingSeparationWithLidEnabled,
            chargingSeparationControlSupported =
                chargingSeparationControlSupported
        )
    }

    private fun applyClosedLidChargingSeparation(reason: String) {
        val manageChargingSeparation =
            AppPreferences.manageChargingSeparationWithLid(this)
        val lidSupported = LidMonitor.isSupported()
        val chargingControlSupported =
            DeviceControlController.supportsChargingSeparationControl(this)
        val dockBypass =
            manageChargingSeparation &&
                lidSupported &&
                chargingControlSupported &&
                clamshellLidState.isClosed &&
                hasExternalDisplayConnected()

        if (dockBypass) {
            DiagnosticsStateStore.recordEvent(
                this,
                "Lid closed dock → Charging Separation unchanged"
            )
        }

        if (
            !manageChargingSeparation ||
            !lidSupported ||
            !chargingControlSupported ||
            !clamshellLidState.isClosed ||
            dockBypass
        ) {
            restoreOwnedChargingSeparation(reason)
            return
        }

        val existingOwnership =
            DeviceControlStore.chargingSeparation(this)
        if (existingOwnership.owned) {
            val current =
                DeviceControlController.chargingSeparationState(this)
            when (
                ChargingSeparationPolicy.ownedApplyDecision(
                    previous = existingOwnership.previous,
                    current = current
                )
            ) {
                ChargingSeparationOwnedApplyDecision.ALREADY_DISABLED ->
                    return

                ChargingSeparationOwnedApplyDecision.REAPPLY_DISABLED -> {
                    if (
                        DeviceControlController
                            .setChargingSeparationEnabled(false)
                    ) {
                        Log.i(
                            TAG,
                            "Charging Separation sleep state re-applied ($reason)"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Charging Separation ownership exists but OFF state could not be confirmed ($reason)"
                        )
                    }
                }

                ChargingSeparationOwnedApplyDecision.CANNOT_CONFIRM_DISABLED -> {
                    Log.w(
                        TAG,
                        "Charging Separation ownership exists but OFF state could not be confirmed ($reason)"
                    )
                }
            }
            return
        }

        val previous =
            DeviceControlController.chargingSeparationState(this)
        if (
            !ChargingSeparationPolicy.shouldTakeOwnershipAndDisable(
                current = previous
            )
        ) {
            return
        }

        DeviceControlStore.takeChargingSeparationOwnership(
            this,
            previous = true
        )

        if (DeviceControlController.setChargingSeparationEnabled(false)) {
            Log.i(
                TAG,
                "Charging Separation disabled while lid is closed ($reason)"
            )
            DiagnosticsStateStore.recordEvent(
                this,
                "Lid closed → Charging Separation disabled"
            )
        } else {
            DeviceControlStore.clearChargingSeparationOwnership(this)
            Log.w(TAG, "Unable to disable Charging Separation")
            DiagnosticsStateStore.recordEvent(
                this,
                "Lid closed → Charging Separation disable failed"
            )
        }
    }

    private fun restoreOwnedChargingSeparation(reason: String): Boolean {
        val owned = DeviceControlStore.chargingSeparation(this)
        if (!owned.owned) return true

        val current =
            DeviceControlController.chargingSeparationState(this)
        when (
            ChargingSeparationPolicy.restoreDecision(
                owned = true,
                previous = owned.previous,
                current = current
            )
        ) {
            ChargingSeparationRestoreDecision.NOTHING_TO_RESTORE ->
                return true

            ChargingSeparationRestoreDecision.CLEAR_OWNERSHIP -> {
                DeviceControlStore.clearChargingSeparationOwnership(this)
                return true
            }

            ChargingSeparationRestoreDecision.RESTORE_PREVIOUS -> Unit
        }

        val restored =
            DeviceControlController.setChargingSeparationEnabled(
                owned.previous
            )
        if (restored) {
            DeviceControlStore.clearChargingSeparationOwnership(this)
            Log.i(TAG, "Charging Separation restored ($reason)")
            DiagnosticsStateStore.recordEvent(
                this,
                "$reason → Charging Separation restored"
            )
        } else {
            Log.w(TAG, "Charging Separation restore failed ($reason)")
            DiagnosticsStateStore.recordEvent(
                this,
                "$reason → Charging Separation restore failed"
            )
        }
        return restored
    }

    private fun isTailscaleSleepVerificationPending(): Boolean =
        SleepCycleStore.connectorChange(this, TailscaleConnector.id)
            ?.restoreToken == TailscaleConnector.TOKEN_VERIFY_DISCONNECT

    private fun prepareTailscaleVerificationForWake() {
        if (!isTailscaleSleepVerificationPending()) return

        tailscaleVerificationState.markWakeRestoreNeeded()
        scheduleTailscaleSleepVerification(resetAttempts = true)
        Log.i(TAG, "Tailscale disconnect verification continuing during wake")
    }

    private fun scheduleTailscaleSleepVerification(resetAttempts: Boolean) {
        handler.removeCallbacks(tailscaleSleepVerifyRunnable)
        if (resetAttempts) {
            tailscaleVerificationState.resetSleepAttempts()
        }

        acquireSleepTransitionWakeLock(
            TAILSCALE_VERIFY_INTERVAL_MS *
                (TAILSCALE_VERIFY_MAX_ATTEMPTS + 2)
        )
        handler.postDelayed(
            tailscaleSleepVerifyRunnable,
            TAILSCALE_VERIFY_INTERVAL_MS
        )
    }

    private fun verifyTailscaleSleepDisconnect() {
        val change =
            SleepCycleStore.connectorChange(this, TailscaleConnector.id)
                ?: return releaseSleepTransitionWakeLock()

        if (change.restoreToken != TailscaleConnector.TOKEN_VERIFY_DISCONNECT) {
            releaseSleepTransitionWakeLock()
            return
        }

        if (!TailscaleController.isConnected(this)) {
            SleepCycleStore.recordConnectorChange(
                this,
                TailscaleConnector.id,
                TailscaleConnector.TOKEN_RESTORE
            )
            Log.i(TAG, "Tailscale disconnect verified")
            DiagnosticsStateStore.recordEvent(
                this,
                "Sleep → Tailscale disconnected"
            )
            continueAfterTailscaleSleepVerification()
            return
        }

        val sleepVerifyAttempts =
            tailscaleVerificationState.incrementSleepAttempts()
        if (sleepVerifyAttempts < TAILSCALE_VERIFY_MAX_ATTEMPTS) {
            handler.postDelayed(
                tailscaleSleepVerifyRunnable,
                TAILSCALE_VERIFY_INTERVAL_MS
            )
            return
        }

        SleepCycleStore.clearConnectorChange(this, TailscaleConnector.id)
        Log.i(
            TAG,
            "Tailscale disconnect not verified; no restore will be scheduled"
        )
        DiagnosticsStateStore.recordEvent(
            this,
            "Sleep → Tailscale unchanged"
        )
        continueAfterTailscaleSleepVerification()
    }

    private fun continueAfterTailscaleSleepVerification() {
        handler.removeCallbacks(tailscaleSleepVerifyRunnable)
        tailscaleVerificationState.resetSleepAttempts()
        releaseSleepTransitionWakeLock()

        val realWake = isRealWakeNow()
        val needsWakeRestore =
            tailscaleVerificationState.consumeWakeRestoreNeeded()

        if (realWake || needsWakeRestore) {
            sleepStopWaitState.clearPendingActions()

            val shouldRestoreNow =
                needsWakeRestore &&
                    hasPendingNetworkConnectorRestore()

            if (shouldRestoreNow) {
                waitForNetworkAndRestorePendingConnectors()
            } else {
                SleepCycleStore.completeIfRestored(this)
                finishDisableRestoreIfRequested()
            }
            return
        }

        if (sleepStopWaitState.pendingPostStopActions) {
            scheduleSleepRadioStopCheck(0L)
        } else {
            SleepCycleStore.completeIfRestored(this)
        }
    }

    private fun cancelTailscaleVerification() {
        handler.removeCallbacks(tailscaleSleepVerifyRunnable)
        handler.removeCallbacks(tailscaleWakeVerifyRunnable)
        tailscaleVerificationState.reset()
    }

    private fun restorePendingJamesDsp() {
        val change =
            SleepCycleStore.connectorChange(this, JamesDspConnector.id)
                ?: return

        val wakeResult =
            JamesDspConnector.wake(this, change.restoreToken)

        if (wakeResult.success) {
            SleepCycleStore.clearConnectorChange(this, JamesDspConnector.id)
            if (!disableRestoreState.isRequested) {
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Wake → JamesDSP restored"
                )
            }
            Log.i(TAG, "JamesDSP power ON sent")
        } else {
            SleepCycleStore.markRestoreProblem(
                this,
                "JamesDSP restore is still pending: ${wakeResult.detail}."
            )
            DiagnosticsStateStore.recordEvent(
                this,
                if (disableRestoreState.isRequested) {
                    "Disable → JamesDSP restore pending"
                } else {
                    "Wake → JamesDSP restore pending"
                }
            )
            Log.w(TAG, "JamesDSP restore failed; preserving transaction")
        }
    }

    private fun resetBasicSyncWakeRestoreConfirmation() {
        handler.removeCallbacks(basicSyncWakeRestoreRunnable)
        basicSyncWakeRestoreStartedAt = 0L
    }

    private fun restorePendingBasicSync() {
        val change =
            SleepCycleStore.connectorChange(this, BasicSyncConnector.id)
                ?: run {
                    resetBasicSyncWakeRestoreConfirmation()
                    return
                }

        val wakeResult =
            BasicSyncConnector.wake(this, change.restoreToken)

        if (!wakeResult.success) {
            resetBasicSyncWakeRestoreConfirmation()
            SleepCycleStore.markRestoreProblem(
                this,
                "BasicSync restore is still pending: ${wakeResult.detail}."
            )
            DiagnosticsStateStore.recordEvent(
                this,
                if (disableRestoreState.isRequested) {
                    "Disable → BasicSync restore pending"
                } else {
                    "Wake → BasicSync restore pending"
                }
            )
            Log.w(TAG, "BasicSync restore failed; preserving transaction")
            return
        }

        val restoreTarget =
            BasicSyncConnector.restoreTargetName(change.restoreToken)

        if (!BasicSyncController.supportsStateApi(this)) {
            SleepCycleStore.clearConnectorChange(this, BasicSyncConnector.id)
            if (!disableRestoreState.isRequested) {
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Wake → BasicSync restored · $restoreTarget"
                )
            }
            Log.i(
                TAG,
                "BasicSync restore sent without state API confirmation: $restoreTarget"
            )
            return
        }

        BasicSyncController.startStateObserver(this)
        resetBasicSyncWakeRestoreConfirmation()
        basicSyncWakeRestoreStartedAt = System.currentTimeMillis()
        BasicSyncController.requestStateBroadcast(this)
        handler.postDelayed(
            basicSyncWakeRestoreRunnable,
            BASIC_SYNC_WAKE_RESTORE_CONFIRM_INTERVAL_MS
        )
        Log.i(
            TAG,
            "BasicSync restore sent: $restoreTarget; awaiting state confirmation"
        )
    }

    private fun verifyPendingBasicSyncRestore() {
        val change =
            SleepCycleStore.connectorChange(this, BasicSyncConnector.id)
                ?: run {
                    resetBasicSyncWakeRestoreConfirmation()
                    return
                }

        val restoreTarget =
            BasicSyncConnector.restoreTargetName(change.restoreToken)
        val state = BasicSyncController.lastObservedState()

        if (
            BasicSyncConnector.restoreConfirmed(
                change.restoreToken,
                state
            )
        ) {
            SleepCycleStore.clearConnectorChange(this, BasicSyncConnector.id)
            resetBasicSyncWakeRestoreConfirmation()

            if (!disableRestoreState.isRequested) {
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Wake → BasicSync restored · $restoreTarget"
                )
            }
            Log.i(
                TAG,
                "BasicSync restore confirmed: $restoreTarget " +
                    "runState=${state?.runState}"
            )
            SleepCycleStore.completeIfRestored(this)
            finishDisableRestoreIfRequested()
            return
        }

        val startedAt =
            basicSyncWakeRestoreStartedAt.takeIf { it > 0L }
                ?: System.currentTimeMillis().also {
                    basicSyncWakeRestoreStartedAt = it
                }
        val elapsed = System.currentTimeMillis() - startedAt

        if (elapsed >= BASIC_SYNC_WAKE_RESTORE_CONFIRM_TIMEOUT_MS) {
            resetBasicSyncWakeRestoreConfirmation()
            SleepCycleStore.markRestoreProblem(
                this,
                "BasicSync restore is still pending: $restoreTarget was not confirmed."
            )
            DiagnosticsStateStore.recordEvent(
                this,
                if (disableRestoreState.isRequested) {
                    "Disable → BasicSync restore pending · $restoreTarget not confirmed"
                } else {
                    "Wake → BasicSync restore pending · $restoreTarget not confirmed"
                }
            )
            Log.w(
                TAG,
                "BasicSync restore not confirmed; preserving transaction " +
                    "target=$restoreTarget state=$state"
            )
            finishDisableRestoreIfRequested(
                forceStop = disableRestoreState.isRequested
            )
            return
        }

        BasicSyncController.requestStateBroadcast(this)
        handler.postDelayed(
            basicSyncWakeRestoreRunnable,
            BASIC_SYNC_WAKE_RESTORE_CONFIRM_INTERVAL_MS
        )
    }

    private fun hasPendingNetworkConnectorRestore(): Boolean {
        val syncthingPending =
            SleepCycleStore.hasConnectorChange(
                this,
                SyncthingConnector.id
            )
        val tailscalePending =
            SleepCycleStore.connectorChange(
                this,
                TailscaleConnector.id
            )?.restoreToken == TailscaleConnector.TOKEN_RESTORE
        return syncthingPending || tailscalePending
    }

    private fun scheduleTailscaleWakeVerification() {
        handler.removeCallbacks(tailscaleWakeVerifyRunnable)
        tailscaleVerificationState.resetWakeAttempts()
        handler.postDelayed(
            tailscaleWakeVerifyRunnable,
            TAILSCALE_VERIFY_INTERVAL_MS
        )
    }

    private fun verifyTailscaleWakeReconnect() {
        val change =
            SleepCycleStore.connectorChange(this, TailscaleConnector.id)
                ?: return

        if (change.restoreToken != TailscaleConnector.TOKEN_RESTORE) {
            return
        }

        if (TailscaleController.isConnected(this)) {
            SleepCycleStore.clearConnectorChange(this, TailscaleConnector.id)
            Log.i(TAG, "Tailscale reconnect verified")

            if (!disableRestoreState.isRequested) {
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Wake → Tailscale restored"
                )
            }

            val complete = SleepCycleStore.completeIfRestored(this)
            if (disableRestoreState.isRequested && !complete) {
                finishDisableRestoreIfRequested(forceStop = true)
            } else {
                finishDisableRestoreIfRequested()
            }
            return
        }

        val wakeVerifyAttempts =
            tailscaleVerificationState.incrementWakeAttempts()

        if (
            wakeVerifyAttempts ==
            TAILSCALE_WAKE_RETRY_AT_ATTEMPT
        ) {
            val retrySent = TailscaleController.sendConnect(this)
            Log.i(
                TAG,
                "Tailscale reconnect still pending -> CONNECT retry sent=$retrySent"
            )
        }

        if (wakeVerifyAttempts < TAILSCALE_WAKE_MAX_ATTEMPTS) {
            handler.postDelayed(
                tailscaleWakeVerifyRunnable,
                TAILSCALE_VERIFY_INTERVAL_MS
            )
            return
        }

        Log.w(TAG, "Tailscale reconnect not verified; restore remains pending")
        DiagnosticsStateStore.recordEvent(
            this,
            if (disableRestoreState.isRequested) {
                "Disable → Tailscale restore pending · retries exhausted"
            } else {
                "Wake → Tailscale restore pending · retries exhausted"
            }
        )
        finishDisableRestoreIfRequested(forceStop = disableRestoreState.isRequested)
    }

    private fun handlePeriodicSyncAlarm() {
        val powerManager =
            getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = powerManager?.isInteractive == true
        val closedLidWakeSuppressed = isClosedLidFalseWakeNow()

        when (
            SyncMaintenancePolicy.periodicAlarmDeviceDecision(
                interactive = interactive,
                closedLidWakeSuppressed = closedLidWakeSuppressed
            )
        ) {
            PeriodicAlarmDeviceDecision.RETRY_AFTER_CLOSED_LID_FALSE_WAKE -> {
                SyncMaintenanceScheduler.scheduleClosedLidFalseWakeRetry(this)
                Log.i(
                    TAG,
                    "Periodic sync deferred: transient closed-lid false wake"
                )
                return
            }

            PeriodicAlarmDeviceDecision.CANCEL_AWAKE -> {
                SyncMaintenanceScheduler.cancel(this)
                Log.i(TAG, "Periodic sync ignored: device is genuinely awake")
                return
            }

            PeriodicAlarmDeviceDecision.RUN -> Unit
        }

        if (!SyncMaintenanceScheduler.canArm(this)) {
            SyncMaintenanceScheduler.cancel(this)
            Log.i(TAG, "Periodic sync ignored: feature unavailable")
            return
        }

        val conditions = SleepConditionEvaluator.evaluate(this)
        if (!conditions.met) {
            val reason = conditions.failedReasons.joinToString(" · ")
            DiagnosticsStateStore.recordEvent(
                this,
                "Periodic sync skipped → $reason"
            )
            Log.i(
                TAG,
                "Periodic sync skipped by Advanced sleep conditions -> $reason"
            )
            SyncMaintenanceScheduler.scheduleNext(this)
            return
        }

        if (syncMaintenanceRunner?.active == true) {
            Log.i(TAG, "Periodic sync ignored: maintenance already active")
            SyncMaintenanceScheduler.scheduleNext(this)
            return
        }

        val batterySaverTemporarilyRestored =
            temporarilyRestoreBatterySaverForMaintenance()

        val started =
            syncRunner().start(
                SyncMaintenanceTrigger.PERIODIC_SLEEP
            ) { snapshot ->
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Periodic sync → ${snapshot.outcome}"
                )

                if (batterySaverTemporarilyRestored) {
                    reapplyBatterySaverAfterMaintenance()
                }

                val stillSleeping = isEffectivelySleepingNow()

                when (
                    SyncMaintenancePolicy.periodicCompletionFollowUp(
                        stillSleeping = stillSleeping,
                        periodicEnabled =
                            AppPreferences.periodicSyncWhileSleeping(this)
                    )
                ) {
                    PeriodicMaintenanceFollowUp.SCHEDULE_NEXT ->
                        SyncMaintenanceScheduler.scheduleNext(this)
                    PeriodicMaintenanceFollowUp.CANCEL ->
                        SyncMaintenanceScheduler.cancel(this)
                }
            }

        if (!started) {
            if (batterySaverTemporarilyRestored) {
                reapplyBatterySaverAfterMaintenance()
            }
            SyncMaintenanceScheduler.scheduleNext(this)
        }
    }

    private fun scheduleSleepDelay(delayMs: Long) {
        cancelSleepDelay()
        sleepDelayState.markPending()

        if (delayMs <= 10_000L) {
            acquireSleepTransitionWakeLock(
                delayMs + SLEEP_TRANSITION_WAKELOCK_TIMEOUT_MS
            )
            handler.postDelayed(sleepGraceRunnable, delayMs)
            return
        }

        val alarmManager =
            getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return

        val triggerAt =
            SystemClock.elapsedRealtime() + delayMs

        val canScheduleExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()

        if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                sleepDelayPendingIntent()
            )
            Log.i(TAG, "Custom sleep delay scheduled as exact alarm")
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                sleepDelayPendingIntent()
            )
            Log.w(
                TAG,
                "Exact alarm access unavailable; custom sleep delay may be deferred"
            )
        }
    }

    private fun cancelSleepDelay() {
        handler.removeCallbacks(sleepGraceRunnable)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(sleepDelayPendingIntent())
        alarmManager?.cancel(legacySleepDelayPendingIntent())
        sleepDelayState.clear()
        releaseSleepTransitionWakeLock()
    }

    private fun sleepDelayPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            SLEEP_DELAY_REQUEST_CODE,
            Intent(this, SleepDelayReceiver::class.java)
                .setAction(ACTION_SLEEP_DELAY_ELAPSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun legacySleepDelayPendingIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            SLEEP_DELAY_REQUEST_CODE,
            Intent(this, SleepManagerService::class.java)
                .setAction(ACTION_SLEEP_DELAY_ELAPSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun acquireSleepTransitionWakeLock(
        timeoutMs: Long = SLEEP_TRANSITION_WAKELOCK_TIMEOUT_MS
    ) {
        releaseSleepTransitionWakeLock()

        val powerManager =
            getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

        sleepTransitionWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:sleep-transition"
        ).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }

        Log.i(TAG, "Sleep transition wakelock acquired")
    }

    private fun releaseSleepTransitionWakeLock() {
        val wakeLock = sleepTransitionWakeLock ?: return

        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
        }
        sleepTransitionWakeLock = null
    }

    private fun onScreenOn() {
        val falseWake = isClosedLidFalseWakeNow()
        val wakeDecision =
            SleepWakePolicy.onScreenOn(
                closedLidProtectionEnabled = falseWake,
                lidClosed = falseWake,
                sleepDelayPending = sleepDelayState.isPending
            )

        behaviorHistory?.wake(suppressed = wakeDecision.suppressWake)
        if (wakeDecision.suppressWake) {
            val preserveSleepTransaction =
                SleepWakePolicy.shouldPreserveSleepTransactionOnFalseWake(
                    suppressWake = true,
                    sleepDelayPending = sleepDelayState.isPending,
                    cycleActive = SleepCycleStore.isActive(this),
                    actionsApplied = sleepCycleRuntimeState.actionsApplied,
                    stopWaitPending = sleepStopWaitState.pendingPostStopActions
                )
            if (preserveSleepTransaction) {
                sleepCycleRuntimeState.markFalseWakeResleepPending()
            }

            DiagnosticsStateStore.recordFalseWake(this)
            BatterySleepStore.noteFalseWake(this)
            DiagnosticsStateStore.recordEvent(
                this,
                "Closed-lid false wake suppressed → returning to sleep"
            )

            Log.i(
                TAG,
                "Screen ON while lid is closed -> false wake suppressed; sleep work preserved"
            )
            handler.removeCallbacks(closedLidScreenOnRecheckRunnable)
            handler.postDelayed(
                closedLidScreenOnRecheckRunnable,
                CLOSED_LID_SCREEN_ON_RECHECK_DELAY_MS
            )
            captureAdvancedDiagnostics(DiagnosticsCycleStore.PHASE_FALSE_WAKE)
            return
        }

        // Only a real wake may cancel sleep-side waits or restoration gates.
        // Advanced diagnostics run off the sleep/wake critical path.
        captureAdvancedDiagnostics(DiagnosticsCycleStore.PHASE_REAL_WAKE)
        sleepCycleRuntimeState.clearFalseWakeResleepPending()
        cancelNetworkReadyWait()
        cancelActiveBasicSyncWait()
        raOfflineProxyCoordinator?.cancelSleepGate(
            clearPersistedState = true
        )
        RaOfflineProxySleepStore.clear(this)

        DeviceControlStore.setBatterySaverDeferredForExternalPower(
            this,
            deferred = false
        )
        restoreOwnedBatterySaver("Wake")

        SyncMaintenanceScheduler.cancel(this)
        cancelSyncMaintenance(restoreSleepWifi = false)

        val wakeSyncWasArmed =
            SyncTransitionStore.isWakeSyncArmed(this)
        wakeTransitionSyncState.arm(
            wakeSyncWasArmed = wakeSyncWasArmed,
            syncThenStopAvailable = syncThenStopAvailable()
        )

        if (wakeSyncWasArmed && !wakeTransitionSyncState.isPending) {
            SyncTransitionStore.clear(this)
        }

        val finishedSleepSession = BatterySleepStore.finishSession(this)
        DiagnosticsCycleStore.markWake(
            context = this,
            sleepSession = finishedSleepSession,
            restorationPending = SleepCycleStore.isActive(this)
        )

        if (wakeDecision.cancelSleepDelay) {
            cancelSleepDelay()
            Log.i(TAG, "Sleep delay cancelled by wake")
        }

        clearPendingSleepStopWait()
        syncthingPreSleepState.invalidate()
        releaseSleepTransitionWakeLock()

        sleepCycleRuntimeState.clearActionsApplied()

        if (isTailscaleSleepVerificationPending()) {
            prepareTailscaleVerificationForWake()
        }

        restorePendingJamesDsp()
        restorePendingRaOfflineProxyImmediately()

        if (wakeTransitionSyncState.isPending) {
            SleepCycleStore.clearConnectorChange(
                this,
                BasicSyncConnector.id
            )
            Log.i(
                TAG,
                "Wake sync mode active; BasicSync restore token cleared"
            )
        } else {
            restorePendingBasicSync()
        }

        var cycle = SleepCycleStore.current(this)
        if (
            cycle.active &&
            DirectRadioStore.isPendingForCycle(this, cycle.cycleId)
        ) {
            val restored =
                RadioController.restoreDirect(this, cycle.cycleId)
            DiagnosticsStateStore.recordEvent(
                this,
                "Wake → PServer radios " +
                    if (restored) "restored" else "restore pending"
            )
            if (!restored) {
                SleepCycleStore.markRestoreProblem(
                    this,
                    "Direct Wi-Fi / Bluetooth restore is still pending."
                )
            }
            cycle = SleepCycleStore.current(this)
        }
        if (
            cycle.active &&
            cycle.helperExpected &&
            !cycle.helperSleepRequested &&
            !cycle.helperRestored
        ) {
            SleepCycleStore.markHelperRestored(this)
            cycle = SleepCycleStore.current(this)
        }

        val helperRestoreNeeded =
            cycle.active &&
                cycle.helperExpected &&
                cycle.helperSleepRequested &&
                !cycle.helperRestored
        val networkRestoreNeeded = hasPendingNetworkConnectorRestore()

        Log.i(
            TAG,
            "Screen ON -> restoring cycle=${cycle.cycleId} active=${cycle.active} " +
                "helperRestoreNeeded=$helperRestoreNeeded " +
                "networkRestoreNeeded=$networkRestoreNeeded"
        )

        helperWakeResultState.beginWake()

        helperNetworkRestoreHandoffState.prepare(
            helperRestoreNeeded = helperRestoreNeeded,
            networkRestoreNeeded = networkRestoreNeeded
        )

        val helperSent = if (helperRestoreNeeded) {
            HelperController.sendWake(this, cycle.cycleId)
        } else {
            false
        }
        if (helperRestoreNeeded) {
            armHelperRestoreRetry(
                cycleId = cycle.cycleId,
                requestSent = helperSent
            )
        } else {
            helperRestoreRetryState.clear()
            handler.removeCallbacks(helperRestoreRetryRunnable)
        }

        if (!helperSent) {
            helperWakeResultState.markHelperUnavailable()
            helperNetworkRestoreHandoffState.clear()

            if (helperRestoreNeeded) {
                Log.w(
                    TAG,
                    "Helper restore still pending; preserving sleep transaction"
                )
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Wake → Helper restore pending"
                )
            }

            if (networkRestoreNeeded) {
                waitForNetworkAndRestorePendingConnectors()
                maybeStartWakeTransitionSync()
            } else {
                if (
                    !helperRestoreNeeded &&
                    !sleepCycleRuntimeState.skippedByConditions &&
                    !SleepCycleStore.hasPendingConnectorChanges(this)
                ) {
                    DiagnosticsStateStore.recordEvent(
                        this,
                        buildWakeSummary(
                            wifiManaged = false,
                            wifiChanged = false,
                            bluetoothManaged = false,
                            bluetoothChanged = false,
                            syncthing = false
                        )
                    )
                }
                SleepCycleStore.completeIfRestored(this)
                finishDisableRestoreIfRequested()
                maybeStartWakeTransitionSync()
            }
        } else if (networkRestoreNeeded) {
            Log.i(TAG, "Connector restore waiting for Helper wake result")
        } else {
            SleepCycleStore.completeIfRestored(this)
            finishDisableRestoreIfRequested()
        }

        sleepCycleRuntimeState.clearSkippedByConditions()
    }

    private fun maybeStartWakeTransitionSync() {
        if (!wakeTransitionSyncState.isPending) return
        if (!syncThenStopAvailable()) {
            wakeTransitionSyncState.clear()
            return
        }

        if (!isRealWakeNow()) {
            return
        }

        if (syncMaintenanceRunner?.active == true) {
            return
        }

        wakeTransitionSyncState.clear()

        val started =
            syncRunner().start(
                SyncMaintenanceTrigger.AFTER_WAKE
            ) { snapshot ->
                SyncTransitionStore.clear(this)
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Wake sync → ${snapshot.outcome} · clients stopped"
                )
            }

        if (started) {
            Log.i(TAG, "Wake sync maintenance started")
        } else {
            SyncTransitionStore.clear(this)
        }
    }

    private fun restorePendingSyncthingBeforeNetworkWait() {
        val change =
            SleepCycleStore.connectorChange(
                this,
                SyncthingConnector.id
            ) ?: return

        val wakeResult =
            SyncthingConnector.wake(
                this,
                change.restoreToken
            )

        if (wakeResult.success) {
            SleepCycleStore.clearConnectorChange(
                this,
                SyncthingConnector.id
            )
            Log.i(
                TAG,
                "Syncthing FOLLOW sent before validated-network wait; " +
                    "network readiness delegated to Syncthing-Fork"
            )

            if (!disableRestoreState.isRequested) {
                DiagnosticsStateStore.recordEvent(
                    this,
                    buildWakeSummary(
                        wifiManaged = helperWakeResultState.wifiManaged,
                        wifiChanged = helperWakeResultState.wifiChanged,
                        wifiAttempted = helperWakeResultState.wifiAttempted,
                        wifiToggleSuccess = helperWakeResultState.wifiToggleSuccess,
                        wifiAirplaneMode = helperWakeResultState.wifiAirplaneMode,
                        bluetoothManaged = helperWakeResultState.bluetoothManaged,
                        bluetoothChanged = helperWakeResultState.bluetoothChanged,
                        syncthing = true
                    )
                )
            }
        } else {
            Log.w(
                TAG,
                "Immediate Syncthing FOLLOW failed; keeping restore pending for network-ready retry"
            )
        }
    }

    private fun waitForNetworkAndRestorePendingConnectors() {
        restorePendingSyncthingBeforeNetworkWait()

        if (!hasPendingNetworkConnectorRestore()) {
            SleepCycleStore.completeIfRestored(this)
            finishDisableRestoreIfRequested()
            return
        }

        cancelNetworkReadyWait()

        networkReadyGate = NetworkReadyGate(
            context = this,
            handler = handler,
            timeoutMs = NETWORK_READY_TIMEOUT_MS
        ) { result ->
            networkReadyGate = null

            val realWake = isRealWakeNow()

            if (!realWake) {
                Log.i(
                    TAG,
                    "Network ready result=$result while device is not in a real wake; restore deferred"
                )
            } else {
                Log.i(
                    TAG,
                    "Network ready result=$result -> restoring pending connectors"
                )

                var restoreFailed = false
                var tailscaleVerificationScheduled = false

                val syncthingChange =
                    SleepCycleStore.connectorChange(
                        this,
                        SyncthingConnector.id
                    )
                if (syncthingChange != null) {
                    val wakeResult =
                        SyncthingConnector.wake(
                            this,
                            syncthingChange.restoreToken
                        )

                    if (wakeResult.success) {
                        SleepCycleStore.clearConnectorChange(
                            this,
                            SyncthingConnector.id
                        )

                        if (!disableRestoreState.isRequested) {
                            DiagnosticsStateStore.recordEvent(
                                this,
                                buildWakeSummary(
                                    wifiManaged = helperWakeResultState.wifiManaged,
                                    wifiChanged = helperWakeResultState.wifiChanged,
                                    wifiAttempted = helperWakeResultState.wifiAttempted,
                                    wifiToggleSuccess = helperWakeResultState.wifiToggleSuccess,
                                    wifiAirplaneMode = helperWakeResultState.wifiAirplaneMode,
                                    bluetoothManaged = helperWakeResultState.bluetoothManaged,
                                    bluetoothChanged = helperWakeResultState.bluetoothChanged,
                                    syncthing = true
                                )
                            )
                        }
                    } else {
                        restoreFailed = true
                        SleepCycleStore.markRestoreProblem(
                            this,
                            "Syncthing restore is still pending: ${wakeResult.detail}."
                        )
                        Log.w(
                            TAG,
                            "Syncthing restore failed; preserving pending connector transaction"
                        )
                        DiagnosticsStateStore.recordEvent(
                            this,
                            if (disableRestoreState.isRequested) {
                                "Disable → Syncthing restore pending"
                            } else {
                                "Wake → Syncthing restore pending"
                            }
                        )
                    }
                }

                val tailscaleChange =
                    SleepCycleStore.connectorChange(
                        this,
                        TailscaleConnector.id
                    )
                if (
                    tailscaleChange?.restoreToken ==
                    TailscaleConnector.TOKEN_RESTORE
                ) {
                    val wakeResult =
                        TailscaleConnector.wake(
                            this,
                            tailscaleChange.restoreToken
                        )

                    if (wakeResult.success) {
                        Log.i(
                            TAG,
                            "Tailscale CONNECT sent -> waiting for VPN verification"
                        )
                        tailscaleVerificationScheduled = true
                        scheduleTailscaleWakeVerification()
                    } else {
                        restoreFailed = true
                        SleepCycleStore.markRestoreProblem(
                            this,
                            "Tailscale restore is still pending: ${wakeResult.detail}."
                        )
                        Log.w(
                            TAG,
                            "Tailscale reconnect request failed; preserving transaction"
                        )
                        DiagnosticsStateStore.recordEvent(
                            this,
                            if (disableRestoreState.isRequested) {
                                "Disable → Tailscale restore pending"
                            } else {
                                "Wake → Tailscale restore pending"
                            }
                        )
                    }
                }

                if (!tailscaleVerificationScheduled) {
                    SleepCycleStore.completeIfRestored(this)
                    finishDisableRestoreIfRequested(
                        forceStop =
                            disableRestoreState.isRequested && restoreFailed
                    )
                }
            }
        }.also { it.start() }
    }

    private fun cancelNetworkReadyWait() {
        networkReadyGate?.cancel()
        networkReadyGate = null
    }

    private fun refreshLidMonitor() {
        if (!shouldMonitorLid()) {
            restoreOwnedChargingSeparation("Lid automation disabled")
            stopLidMonitor()
            return
        }

        registerDisplayListener()
        if (AppPreferences.manageClosedLidProtection(this)) {
            startPowerButtonMonitor()
        } else {
            clamshellMonitorRuntime.stopPowerButtonMonitor()
        }

        if (clamshellMonitorRuntime.hasLidMonitor) {
            applyClosedLidChargingSeparation("settings refresh")
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val currentLidState = LidMonitor.readCurrentLidClosed()
        observedHistoryLidClosed = currentLidState
        behaviorHistory?.lid(currentLidState)
        clamshellLidState.initialize(
            currentLidState = currentLidState,
            interactive = powerManager?.isInteractive,
            lastKnownLidClosed = ClamshellStateStore.lastKnownLidClosed(this)
        )
        currentLidState?.let {
            ClamshellStateStore.setLastKnownLidClosed(this, it)
        }

        clamshellDisplayState.updateExternalDisplay(
            connected = hasExternalDisplayConnected(),
            active = hasActiveExternalDisplay()
        )
        applyClosedLidChargingSeparation("service start")

        val monitor = LidMonitor(
            onClosed = {
                handler.post {
                    clamshellLidState.markClosed()
                    observedHistoryLidClosed = true
                    behaviorHistory?.lid(true)
                    closedLidState.clearAwakeOverride()
                    closedLidState.clearSleepIntent()
                    ClamshellStateStore.setLastKnownLidClosed(
                        this@SleepManagerService,
                        true
                    )
                    handler.removeCallbacks(closedLidGuardRunnable)
                    handler.removeCallbacks(closedLidScreenOnRecheckRunnable)

                    clamshellDisplayState.updateExternalDisplay(
                        connected = hasExternalDisplayConnected(),
                        active = hasActiveExternalDisplay()
                    )
                    Log.i(TAG, "SW_LID -> CLOSED")
                    applyClosedLidChargingSeparation("lid closed")

                    if (
                        AppPreferences.manageClosedLidProtection(this@SleepManagerService)
                    ) {
                        if (clamshellDisplayState.externalDisplayConnected) {
                            Log.i(
                                TAG,
                                if (clamshellDisplayState.externalDisplayActive) {
                                    "Dock mode -> external display active; lid close ignored"
                                } else {
                                    "Dock mode -> external display connected; lid close ignored"
                                }
                            )
                        } else {
                            handler.postDelayed(
                                closedLidGuardRunnable,
                                CLOSED_LID_GUARD_DELAY_MS
                            )
                        }
                    }
                }
            },
            onOpened = {
                handler.post {
                    clamshellLidState.markOpened()
                    observedHistoryLidClosed = false
                    behaviorHistory?.lid(false)
                    closedLidState.clearAwakeOverride()
                    closedLidState.clearSleepIntent()
                    closedLidState.clearSleepRequestPending()
                    ClamshellStateStore.setLastKnownLidClosed(
                        this@SleepManagerService,
                        false
                    )
                    handler.removeCallbacks(closedLidGuardRunnable)
                    handler.removeCallbacks(closedLidScreenOnRecheckRunnable)
                    handler.removeCallbacks(dockDisconnectRunnable)
                    restoreOwnedChargingSeparation("Lid opened")
                    Log.i(TAG, "SW_LID -> OPEN")
                }
            },
            onError = { error ->
                Log.e(TAG, "Lid monitor failed", error)
                handler.post {
                    observedHistoryLidClosed = null
                    behaviorHistory?.lid(null)
                    DiagnosticsStateStore.recordEvent(
                        this,
                        "Closed-lid monitoring unavailable"
                    )
                    restoreOwnedChargingSeparation("Lid monitor error")
                    stopLidMonitor()
                }
            }
        )

        if (monitor.start()) {
            clamshellMonitorRuntime.attachLidMonitor(monitor)
            Log.i(
                TAG,
                "Lid monitor started: ${LidMonitor.detectionDescription()}"
            )
        } else {
            Log.w(TAG, "No readable SW_LID input device found")
            restoreOwnedChargingSeparation("Lid monitor unavailable")
        }
    }

    private fun registerDisplayListener() {
        if (clamshellDisplayState.listenerRegistered) return
        val manager = displayManager ?: return

        manager.registerDisplayListener(displayListener, handler)
        clamshellDisplayState.registerListener(
            connected = hasExternalDisplayConnected(),
            active = hasActiveExternalDisplay()
        )
        Log.i(
            TAG,
            "Clamshell display monitor started connected=${clamshellDisplayState.externalDisplayConnected} " +
                "active=${clamshellDisplayState.externalDisplayActive}"
        )
    }

    private fun unregisterDisplayListener() {
        if (!clamshellDisplayState.listenerRegistered) return
        runCatching {
            displayManager?.unregisterDisplayListener(displayListener)
        }
        clamshellDisplayState.unregisterListener()
    }

    private fun startPowerButtonMonitor() {
        if (clamshellMonitorRuntime.hasPowerButtonMonitor) return

        val monitor = PmicPowerButtonMonitor(
            onPressed = {
                handler.post { handleClosedLidPowerButtonPressed() }
            },
            onError = { error ->
                Log.e(TAG, "PMIC power button monitor failed", error)
                handler.post {
                    clamshellMonitorRuntime.stopPowerButtonMonitor()
                }
            }
        )

        if (monitor.start()) {
            clamshellMonitorRuntime.attachPowerButtonMonitor(monitor)
            Log.i(
                TAG,
                "PMIC power button monitor started on " +
                    PmicPowerButtonMonitor.findPowerButtonDevicePath()
            )
        } else {
            Log.w(TAG, "No pmic_pwrkey input device found; closed-lid Power option unavailable")
        }
    }

    private fun isExternalDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        val name = display.name.lowercase()
        return name.contains("dp screen") ||
            name.contains("hdmi") ||
            name.contains("external")
    }

    private fun hasExternalDisplayConnected(): Boolean =
        displayManager
            ?.displays
            ?.any(::isExternalDisplay) == true

    private fun hasActiveExternalDisplay(): Boolean =
        displayManager
            ?.displays
            ?.any { display ->
                isExternalDisplay(display) &&
                    display.state == Display.STATE_ON
            } == true

    private fun refreshExternalDisplayState(reason: String) {
        if (!shouldMonitorLid()) return

        val connected = hasExternalDisplayConnected()
        val active = hasActiveExternalDisplay()
        val wasConnected =
            clamshellDisplayState.updateExternalDisplay(
                connected = connected,
                active = active
            )

        if (connected) {
            handler.removeCallbacks(dockDisconnectRunnable)
            restoreOwnedChargingSeparation("Dock connected")
            if (
                AppPreferences.manageClosedLidProtection(this) &&
                clamshellLidState.isClosed &&
                !closedLidState.sleepIntent
            ) {
                closedLidState.clearAwakeOverride()
                if (active) {
                    Log.i(TAG, "Dock mode -> external display active ($reason)")
                }
            }
            return
        }

        if (
            ClamshellDockPolicy.shouldDebounceDisconnect(
                wasConnected = wasConnected,
                lidClosed = clamshellLidState.isClosed
            )
        ) {
            handler.removeCallbacks(dockDisconnectRunnable)
            handler.postDelayed(
                dockDisconnectRunnable,
                DOCK_DISCONNECT_DEBOUNCE_MS
            )
            Log.i(TAG, "Dock mode -> external display disconnected; debounce started")
        }
    }

    private fun handleDockDisconnect() {
        if (!shouldMonitorLid() || !clamshellLidState.isClosed) return

        if (hasExternalDisplayConnected()) {
            clamshellDisplayState.updateExternalDisplay(
                connected = true,
                active = hasActiveExternalDisplay()
            )
            closedLidState.clearAwakeOverride()
            restoreOwnedChargingSeparation("Dock still connected")
            return
        }

        clamshellDisplayState.clearExternalDisplay()
        applyClosedLidChargingSeparation("Dock disconnected")

        if (!AppPreferences.manageClosedLidProtection(this)) {
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        when (
            ClamshellDockPolicy.disconnectDecision(
                interactive = powerManager.isInteractive,
                sleepWhenDisconnected = AppPreferences.dockDisconnectSleeps(this)
            )
        ) {
            DockDisconnectDecision.ALREADY_ASLEEP -> {
                closedLidState.clearAwakeOverride()
            }

            DockDisconnectDecision.REQUEST_SLEEP -> {
                closedLidState.clearAwakeOverride()
                closedLidState.markSleepIntent()
                Log.i(TAG, "Dock mode -> display disconnected; requesting sleep")
                requestClosedLidSleep("dock display disconnected")
            }

            DockDisconnectDecision.KEEP_AWAKE -> {
                closedLidState.clearSleepIntent()
                closedLidState.markAwakeOverride()
                Log.i(TAG, "Dock mode -> display disconnected; keeping awake")
                DiagnosticsStateStore.recordEvent(
                    this,
                    "Dock → display disconnected · kept awake"
                )
            }
        }
    }

    private fun handleClosedLidPowerButtonPressed() {
        if (!AppPreferences.manageClosedLidProtection(this) || !clamshellLidState.isClosed) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!powerManager.isInteractive) return

        if (!AppPreferences.closedLidPowerSleeps(this)) {
            Log.i(TAG, "KEY_POWER while lid closed -> device default")
            return
        }

        closedLidState.clearAwakeOverride()
        closedLidState.markSleepIntent()
        Log.i(TAG, "KEY_POWER while lid closed -> requesting sleep")
        requestClosedLidSleep("closed-lid power button")
    }

    private fun shouldBypassClosedLidProtection(): Boolean {
        if (!clamshellLidState.isClosed || closedLidState.sleepIntent) return false
        return hasExternalDisplayConnected() || closedLidState.awakeOverride
    }

    private fun isClosedLidFalseWakeNow(): Boolean {
        val interactive =
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isInteractive == true
        return SleepWakePolicy.isSuppressedClosedLidFalseWake(
            interactive = interactive,
            closedLidProtectionEnabled = AppPreferences.manageClosedLidProtection(this),
            lidClosed = clamshellLidState.isClosed,
            bypassClosedLidProtection =
                shouldBypassClosedLidProtection()
        )
    }

    private fun isEffectivelySleepingNow(): Boolean {
        val interactive =
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isInteractive == true
        return SleepWakePolicy.isEffectivelySleeping(
            interactive = interactive,
            closedLidProtectionEnabled = AppPreferences.manageClosedLidProtection(this),
            lidClosed = clamshellLidState.isClosed,
            bypassClosedLidProtection =
                shouldBypassClosedLidProtection()
        )
    }

    private fun isRealWakeNow(): Boolean {
        val interactive =
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isInteractive == true
        return SleepWakePolicy.isRealWake(
            interactive = interactive,
            closedLidProtectionEnabled = AppPreferences.manageClosedLidProtection(this),
            lidClosed = clamshellLidState.isClosed,
            bypassClosedLidProtection =
                shouldBypassClosedLidProtection()
        )
    }

    private fun stopLidMonitor() {
        observedHistoryLidClosed = null
        behaviorHistory?.lid(null)
        handler.removeCallbacks(closedLidGuardRunnable)
        handler.removeCallbacks(closedLidScreenOnRecheckRunnable)
        handler.removeCallbacks(dockDisconnectRunnable)
        clamshellLidState.clear()
        closedLidState.clearAwakeOverride()
        closedLidState.clearSleepIntent()
        closedLidState.clearSleepRequestPending()
        clamshellMonitorRuntime.stopAll()
        unregisterDisplayListener()
    }

    private fun maybeReturnDeviceToSleep(reason: String) {
        if (!AppPreferences.manageClosedLidProtection(this) || !clamshellLidState.isClosed) return

        if (shouldBypassClosedLidProtection()) {
            Log.i(TAG, "Closed-lid protection bypassed -> dock/keep-awake state ($reason)")
            return
        }

        requestClosedLidSleep(reason)
    }

    private fun requestClosedLidSleep(reason: String) {
        if (!AppPreferences.manageClosedLidProtection(this) || !clamshellLidState.isClosed) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!powerManager.isInteractive) {
            closedLidState.clearSleepRequestPending()
            return
        }
        if (closedLidState.sleepRequestPending) return

        val now = SystemClock.elapsedRealtime()
        val sinceLastLock = now - closedLidState.lastLockAtElapsed
        if (sinceLastLock < CLOSED_LID_LOCK_COOLDOWN_MS) {
            handler.removeCallbacks(closedLidScreenOnRecheckRunnable)
            handler.postDelayed(
                closedLidScreenOnRecheckRunnable,
                CLOSED_LID_LOCK_COOLDOWN_MS - sinceLastLock + 100L
            )
            return
        }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isAdminActive(closedLidAdminComponent)) {
            Log.w(TAG, "Closed-lid protection skipped: Device Admin not active")
            return
        }

        try {
            closedLidState.markSleepRequestPending()
            closedLidState.markSleepIntent()
            closedLidState.recordLock(now)
            cancelNetworkReadyWait()
            helperNetworkRestoreHandoffState.clear()
            Log.i(TAG, "Closed-lid protection -> lockNow() ($reason)")
            DiagnosticsStateStore.recordEvent(
                this,
                "Protection → device returned to sleep"
            )
            dpm.lockNow()

            handler.removeCallbacks(closedLidScreenOnRecheckRunnable)
            handler.postDelayed(
                closedLidScreenOnRecheckRunnable,
                CLOSED_LID_LOCK_COOLDOWN_MS + 150L
            )
        } catch (t: Throwable) {
            closedLidState.clearSleepRequestPending()
            Log.e(TAG, "Unable to return closed-lid device to sleep", t)
        }
    }

    private fun buildSleepSummary(
        wifiManaged: Boolean,
        wifiChanged: Boolean,
        wifiAttempted: Boolean = false,
        wifiToggleSuccess: Boolean = true,
        wifiAirplaneMode: Boolean = false,
        bluetoothManaged: Boolean,
        bluetoothChanged: Boolean
    ): String {
        val actions = buildList {
            if (wifiManaged) {
                add(
                    when {
                        wifiAttempted && !wifiToggleSuccess ->
                            "Wi-Fi toggle failed" +
                                if (wifiAirplaneMode) {
                                    " · Airplane mode is enabled"
                                } else {
                                    ""
                                }
                        wifiChanged -> "Wi-Fi off"
                        else -> "Wi-Fi unchanged"
                    }
                )
            }
            if (bluetoothManaged) add(if (bluetoothChanged) "Bluetooth off" else "Bluetooth unchanged")
        }
        return if (actions.isEmpty()) "Sleep" else "Sleep → " + actions.joinToString(" · ")
    }

    private fun buildWakeSummary(
        wifiManaged: Boolean,
        wifiChanged: Boolean,
        wifiAttempted: Boolean = false,
        wifiToggleSuccess: Boolean = true,
        wifiAirplaneMode: Boolean = false,
        bluetoothManaged: Boolean,
        bluetoothChanged: Boolean,
        syncthing: Boolean
    ): String {
        val actions = buildList {
            if (wifiManaged) {
                add(
                    when {
                        wifiAttempted && !wifiToggleSuccess ->
                            "Wi-Fi toggle failed" +
                                if (wifiAirplaneMode) {
                                    " · Airplane mode is enabled"
                                } else {
                                    ""
                                }
                        wifiChanged -> "Wi-Fi restored to previous state"
                        else -> "Wi-Fi unchanged"
                    }
                )
            }
            if (bluetoothManaged) add(if (bluetoothChanged) "Bluetooth restored to previous state" else "Bluetooth unchanged")
            if (syncthing) add("Syncthing FOLLOW sent")
        }
        return if (actions.isEmpty()) "Wake" else "Wake → " + actions.joinToString(" · ")
    }

    private fun applyCurrentScreenState() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) onScreenOff() else onScreenOn()
    }

    private fun registerHelperResultReceiver() {
        if (helperResultReceiverRegistered) return

        ContextCompat.registerReceiver(
            this,
            helperResultReceiver,
            IntentFilter(HelperController.ACTION_RESULT),
            HelperController.PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            helperResultReceiverV2,
            IntentFilter(HelperController.ACTION_RESULT_V2),
            HelperController.PERMISSION_V2,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        helperResultReceiverRegistered = true
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // SCREEN_ON/OFF and power connection changes are framework broadcasts.
        // Android 13+ requires an exported context receiver for broadcasts that
        // originate outside this app. These actions are system-defined and the
        // receiver still ignores every action outside the explicit filter above.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SleepManager",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps sleep and wake automation active"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setContentTitle("SleepManager is on")
            .setContentText("Sleep / wake automation is running")
            .setSmallIcon(R.drawable.ic_notification_sleepmanager)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun androidReportsLowMemory(): Boolean {
        val manager =
            getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
        return runCatching {
            ActivityManager.MemoryInfo()
                .also(manager::getMemoryInfo)
                .lowMemory
        }.getOrDefault(false)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (
            level == TRIM_MEMORY_RUNNING_LOW ||
            level == TRIM_MEMORY_RUNNING_CRITICAL ||
            level == TRIM_MEMORY_MODERATE ||
            level == TRIM_MEMORY_COMPLETE
        ) {
            val lowMemory = androidReportsLowMemory()
            DiagnosticsStateStore.recordEvent(
                this,
                if (lowMemory) {
                    "Memory pressure → Android lowMemory=true · onTrimMemory level=$level"
                } else {
                    "Memory trim → onTrimMemory level=$level"
                }
            )
            captureAdvancedDiagnostics(
                phase = DiagnosticsCycleStore.PHASE_MEMORY_PRESSURE,
                trimMemoryLevel = level
            )
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        val lowMemory = androidReportsLowMemory()
        DiagnosticsStateStore.recordEvent(
            this,
            if (lowMemory) {
                "Memory pressure → Android lowMemory=true · onLowMemory"
            } else {
                "Memory trim → onLowMemory"
            }
        )
        captureAdvancedDiagnostics(DiagnosticsCycleStore.PHASE_MEMORY_PRESSURE)
    }

    override fun onDestroy() {
        behaviorHistory?.stop()
        behaviorHistory = null
        wakeTransitionSyncState.clear()
        syncthingPreSleepState.invalidate()

        if (isEffectivelySleepingNow()) {
            val cycle = SleepCycleStore.current(this)
            when {
                cycle.active &&
                    DirectRadioStore.isPendingForCycle(
                        this,
                        cycle.cycleId
                    ) ->
                    RadioController.reapplyDirectSleepState(
                        this,
                        cycle.cycleId
                    )

                cycle.active &&
                    cycle.helperExpected &&
                    !cycle.helperRestored ->
                    HelperController.setTemporaryWifi(
                        this,
                        enabled = false
                    )
            }
        }

        cancelSyncMaintenance(restoreSleepWifi = false)
        cancelNetworkReadyWait()
        helperNetworkRestoreHandoffState.clear()
        disableRestoreState.clearRequest()
        if (!AppPreferences.isEnabled(this)) {
            cancelSleepDelay()
        } else {
            handler.removeCallbacks(sleepGraceRunnable)
            sleepDelayState.clear()
        }
        clearPendingSleepStopWait()
        cancelActiveBasicSyncWait()
        cancelTailscaleVerification()
        handler.removeCallbacks(closedLidGuardRunnable)
        handler.removeCallbacks(closedLidScreenOnRecheckRunnable)
        handler.removeCallbacks(dockDisconnectRunnable)
        handler.removeCallbacks(externalPowerDisconnectRecheckRunnable)
        resetBasicSyncWakeRestoreConfirmation()
        handler.removeCallbacks(ownedBasicSyncRestoreRunnable)
        handler.removeCallbacks(ownedDeviceControlRestoreRunnable)
        handler.removeCallbacks(helperRestoreRetryRunnable)
        helperRestoreRetryState.clear()
        basicSyncRestoreRetryState.clearPending()
        deviceControlRestoreRetryState.clearPending()
        disableRestoreState.clearTransientFlags()
        releaseSleepTransitionWakeLock()
        syncStopProbeExecutor.shutdownNow()
        diagnosticsExecutor.shutdownNow()
        raOfflineProxyCoordinator?.shutdown(
            preservePersistedState = true
        )
        raOfflineProxyCoordinator = null
        BasicSyncController.stopStateObserver()
        stopLidMonitor()

        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }

        if (helperResultReceiverRegistered) {
            try {
                unregisterReceiver(helperResultReceiver)
            } catch (_: IllegalArgumentException) {
            }
            try {
                unregisterReceiver(helperResultReceiverV2)
            } catch (_: IllegalArgumentException) {
            }
            helperResultReceiverRegistered = false
        }

        running = false
        SleepManagerStatusProvider.notifyChanged(this)
        Log.i(TAG, "Service stopped; active sleep transaction preserved")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
