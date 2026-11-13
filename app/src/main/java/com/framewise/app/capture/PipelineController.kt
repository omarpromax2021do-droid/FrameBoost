package com.framewise.app.capture

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.Toast
import com.framewise.app.R
import com.framewise.app.autopilot.AutoPilotManager
import com.framewise.app.autopilot.BoostReport
import com.framewise.app.autopilot.ResolvedAutoPilotPreset
import com.framewise.app.autopilot.SystemOptimizer
import com.framewise.app.autopilot.ThermalGuardMonitor
import com.framewise.app.autopilot.ThermalGuardState
import com.framewise.app.device.DeviceHardwareProfile
import com.framewise.app.device.HardwareTier
import com.framewise.app.device.InterpolationMode
import com.framewise.app.gpu.GLRenderThread
import com.framewise.app.gpu.LiveRenderConfig
import com.framewise.app.gpu.MetricsTracker
import com.framewise.app.gpu.PipelineMetricsSnapshot
import com.framewise.app.settings.AppSettings
import com.framewise.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PipelineSessionState {
    data object Idle : PipelineSessionState
    data object Starting : PipelineSessionState
    data class Running(val metrics: PipelineMetricsSnapshot) : PipelineSessionState
    data class Error(val message: String) : PipelineSessionState
}

object PipelineController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val metricsTracker = MetricsTracker()

    private val _sessionState = MutableStateFlow<PipelineSessionState>(PipelineSessionState.Idle)
    val sessionState: StateFlow<PipelineSessionState> = _sessionState.asStateFlow()

    private val _thermalState = MutableStateFlow(ThermalGuardState())
    val thermalState: StateFlow<ThermalGuardState> = _thermalState.asStateFlow()

    private val _activeAutoPilotPreset = MutableStateFlow<ResolvedAutoPilotPreset?>(null)
    val activeAutoPilotPreset: StateFlow<ResolvedAutoPilotPreset?> = _activeAutoPilotPreset.asStateFlow()

    private val _lastBoostReport = MutableStateFlow<BoostReport?>(null)
    val lastBoostReport: StateFlow<BoostReport?> = _lastBoostReport.asStateFlow()

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    @Volatile
    private var renderThread: GLRenderThread? = null

    @Volatile
    private var currentLiveConfig: LiveRenderConfig = LiveRenderConfig()

    @Volatile
    private var activePresetLabel: String = "30->60 Console"

    @Volatile
    private var activePreferredModeId: Int = 0

    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    @Volatile
    private var latestProfile: DeviceHardwareProfile? = null

    @Volatile
    private var lastBoostedPackage: String? = null

    private var thermalMonitor: ThermalGuardMonitor? = null
    private var thermalCollectJob: Job? = null

    private var pendingSurface: Surface? = null
    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0
    private var metricsTickerJob: Job? = null

    @Synchronized
    fun ensureMonitoringStarted(context: Context) {
        if (thermalMonitor == null) {
            val monitor = ThermalGuardMonitor(context.applicationContext)
            thermalMonitor = monitor
            monitor.start()
            monitor.updatePolicy(latestSettings.thermalGuardEnabled, latestSettings.motionScalePercent)
            thermalCollectJob = scope.launch {
                monitor.state.collect { state ->
                    _thermalState.value = state
                    applyThermalOverlayToLiveConfig(state)
                }
            }
        }
    }

    @Synchronized
    fun syncSettingsAndProfile(settings: AppSettings, profile: DeviceHardwareProfile?) {
        latestSettings = settings
        if (profile != null) {
            latestProfile = profile
        }
        thermalMonitor?.updatePolicy(settings.thermalGuardEnabled, settings.motionScalePercent)
    }

    @Synchronized
    fun updateLiveConfig(
        config: LiveRenderConfig,
        preferredModeId: Int,
        presetLabel: String
    ) {
        val merged = mergeThermalState(config, _thermalState.value)
        currentLiveConfig = merged
        activePreferredModeId = preferredModeId
        activePresetLabel = presetLabel
        renderThread?.updateLiveConfig(merged)

        if (renderThread != null) {
            val surfaceRate = resolveSurfaceFrameRate(merged)
            FrameBoostOverlayService.updateDisplayRate(preferredModeId, surfaceRate)
        }
    }

    fun onForegroundPackageDetected(context: Context, packageName: String) {
        ensureMonitoringStarted(context)
        _foregroundPackage.value = packageName
        if (!latestSettings.autoPilotEnabled) return

        val profile = latestProfile
        val tier = profile?.hardwareTier ?: latestSettings.hardwareTier
        val maxHz = profile?.maxRefreshRateHz ?: latestSettings.targetFps.toFloat().coerceAtLeast(60f)
        val customMap = latestSettings.customProfiles.associateBy { it.packageName }

        val resolved = AutoPilotManager.resolvePresetForPackage(
            packageName = packageName,
            hardwareTier = tier,
            maxRefreshHz = maxHz,
            customProfiles = customMap,
            userHudZones = latestSettings.hudZones
        )

        if (resolved == null) {
            lastBoostedPackage = null
            _activeAutoPilotPreset.value = null
            return
        }

        _activeAutoPilotPreset.value = resolved
        val preferredModeId = profile?.preferredModeIdForTargetFps(resolved.targetFps)
            ?: latestSettings.preferredDisplayModeId

        val updatedConfig = currentLiveConfig.copy(
            mode = resolved.mode,
            motionScalePercent = resolved.motionScalePercent,
            casSharpness = resolved.casSharpness,
            hudMaskEnabled = resolved.hudMaskEnabled,
            hudProtectedRects = resolved.hudZones.toGlUvFloatArray(),
            targetPanelHz = resolved.targetFps.toFloat(),
            minCaptureFpsGateOverride = resolved.minCaptureFpsGate
        )
        updateLiveConfig(updatedConfig, preferredModeId, resolved.presetLabel)

        val isNewLaunch = lastBoostedPackage != packageName
        if (isNewLaunch) {
            lastBoostedPackage = packageName
            scope.launch {
                val repo = SettingsRepository(context.applicationContext)
                repo.applyAutoPilotProfile(
                    mode = resolved.mode,
                    motionScalePercent = resolved.motionScalePercent,
                    casSharpness = resolved.casSharpness,
                    targetFps = resolved.targetFps,
                    preferredModeId = preferredModeId,
                    hudMaskEnabled = resolved.hudMaskEnabled
                )
                val report = SystemOptimizer.optimizeForGame(context.applicationContext, packageName)
                _lastBoostReport.value = report
                mainHandler.post {
                    val toastMsg = context.getString(
                        R.string.autopilot_toast_format,
                        resolved.displayName,
                        report.freedRamMb,
                        report.gameModeLabel
                    )
                    Toast.makeText(context.applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Synchronized
    fun recordBoostReport(report: BoostReport) {
        _lastBoostReport.value = report
    }

    @Synchronized
    fun startRenderPipeline(
        context: Context,
        captureWidth: Int,
        captureHeight: Int,
        targetHz: Float,
        preferredModeId: Int,
        presetLabel: String,
        onInputSurfaceReady: (Surface) -> Unit,
        onError: (String) -> Unit
    ) {
        ensureMonitoringStarted(context)
        stopRenderPipelineOnly()

        activePresetLabel = presetLabel
        activePreferredModeId = preferredModeId
        currentLiveConfig = mergeThermalState(
            currentLiveConfig.copy(targetPanelHz = targetHz),
            _thermalState.value
        )
        metricsTracker.reset()
        _sessionState.value = PipelineSessionState.Starting

        val thread = GLRenderThread(
            context = context.applicationContext,
            initialWidth = captureWidth,
            initialHeight = captureHeight,
            metricsTracker = metricsTracker,
            initialConfig = currentLiveConfig,
            onInputSurfaceReady = { inputSurface ->
                pendingSurface?.let { surf ->
                    if (surf.isValid && pendingWidth > 0 && pendingHeight > 0) {
                        renderThread?.attachOutputSurface(surf, pendingWidth, pendingHeight)
                    }
                }
                onInputSurfaceReady(inputSurface)
            },
            onError = { errorMsg ->
                _sessionState.value = PipelineSessionState.Error(errorMsg)
                onError(errorMsg)
            }
        )
        renderThread = thread
        thread.start()

        val surfaceRate = resolveSurfaceFrameRate(currentLiveConfig)
        FrameBoostOverlayService.showOverlays(preferredModeId = preferredModeId, targetHz = surfaceRate)

        metricsTickerJob = scope.launch {
            while (isActive && renderThread != null) {
                val snapshot = metricsTracker.snapshot(System.nanoTime(), activePresetLabel)
                _sessionState.value = PipelineSessionState.Running(snapshot)
                delay(250L)
            }
        }
    }

    @Synchronized
    fun onOverlaySurfaceAvailable(surface: Surface, width: Int, height: Int) {
        pendingSurface = surface
        pendingWidth = width
        pendingHeight = height
        renderThread?.attachOutputSurface(surface, width, height)
    }

    @Synchronized
    fun onOverlaySurfaceDestroyed() {
        pendingSurface = null
        pendingWidth = 0
        pendingHeight = 0
        renderThread?.detachOutputSurface()
    }

    fun onCapturedContentResized(width: Int, height: Int) {
        renderThread?.updateCaptureSize(width, height)
    }

    fun latestMetricsSnapshot(): PipelineMetricsSnapshot {
        return metricsTracker.snapshot(System.nanoTime(), activePresetLabel)
    }

    fun stopSession(context: Context, errorMessage: String? = null) {
        stopRenderPipelineOnly()
        FrameBoostOverlayService.hideOverlays()
        runCatching {
            val stopIntent = Intent(context, FrameCaptureService::class.java).apply {
                action = FrameCaptureService.ACTION_STOP
            }
            context.startService(stopIntent)
        }
        _sessionState.value = if (errorMessage != null) {
            PipelineSessionState.Error(errorMessage)
        } else {
            PipelineSessionState.Idle
        }
    }

    @Synchronized
    fun stopRenderPipelineOnly() {
        metricsTickerJob?.cancel()
        metricsTickerJob = null
        renderThread?.shutdown()
        renderThread = null
        pendingSurface = null
        pendingWidth = 0
        pendingHeight = 0
    }

    @Synchronized
    private fun applyThermalOverlayToLiveConfig(state: ThermalGuardState) {
        val updated = mergeThermalState(currentLiveConfig, state)
        currentLiveConfig = updated
        renderThread?.updateLiveConfig(updated)
    }

    private fun mergeThermalState(base: LiveRenderConfig, thermal: ThermalGuardState): LiveRenderConfig {
        return if (thermal.isThrottled) {
            base.copy(
                thermalScaleCapPercent = thermal.effectiveMotionScalePercent,
                thermalForceCasOnly = thermal.forceCasOnly,
                thermalWarningBadge = thermal.hudWarningBadge
            )
        } else {
            base.copy(
                thermalScaleCapPercent = null,
                thermalForceCasOnly = false,
                thermalWarningBadge = null
            )
        }
    }

    private fun resolveSurfaceFrameRate(config: LiveRenderConfig): Float {
        return when (config.mode) {
            InterpolationMode.COMPETITIVE_60_120 -> config.targetPanelHz.coerceAtLeast(60f)
            InterpolationMode.CONSOLE_30_60,
            InterpolationMode.STABILIZER_60_CAS -> 60f
        }
    }
}
