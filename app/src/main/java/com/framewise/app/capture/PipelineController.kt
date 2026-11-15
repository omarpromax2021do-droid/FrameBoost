package com.framewise.app.capture

import android.content.Context
import android.content.Intent
import android.view.Surface
import com.framewise.app.autopilot.AutoPilotManager
import com.framewise.app.autopilot.BoostReport
import com.framewise.app.autopilot.OemBoosterSuppressor
import com.framewise.app.autopilot.ResolvedAutoPilotPreset
import com.framewise.app.autopilot.SystemOptimizer
import com.framewise.app.autopilot.ThermalGuardMonitor
import com.framewise.app.autopilot.ThermalGuardState
import com.framewise.app.autopilot.UniversalGameClassifier
import com.framewise.app.device.DeviceHardwareProfile
import com.framewise.app.device.InterpolationMode
import com.framewise.app.gpu.DisplayResizeState
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
import java.util.concurrent.ConcurrentHashMap

sealed interface PipelineSessionState {
    data object Idle : PipelineSessionState
    data object Starting : PipelineSessionState
    data class Running(val metrics: PipelineMetricsSnapshot) : PipelineSessionState
    data class Error(val message: String) : PipelineSessionState
}

enum class HudQuickMode {
    BOOST_120_PLUS,
    SMOOTH_60,
    FSR_CAS_ONLY,
    AUTO
}

object PipelineController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val metricsTracker = MetricsTracker()
    private val packageCooldownTimestamps = ConcurrentHashMap<String, Long>()

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
    private var lastActiveGamePackage: String? = null

    private var thermalMonitor: ThermalGuardMonitor? = null
    private var thermalCollectJob: Job? = null

    private var pendingSurface: Surface? = null
    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0
    private var pendingDensityDpi: Int = 0
    private var metricsTickerJob: Job? = null
    private var oemSuppressorJob: Job? = null

    fun currentMotionScalePercent(): Int = currentLiveConfig.motionScalePercent

    fun isAutoPilotActive(): Boolean {
        return latestSettings.autoPilotEnabled && !latestSettings.manualOverrideActive
    }

    fun activeQuickMode(): HudQuickMode {
        if (isAutoPilotActive()) return HudQuickMode.AUTO
        return when (currentLiveConfig.mode) {
            InterpolationMode.COMPETITIVE_60_120 -> HudQuickMode.BOOST_120_PLUS
            InterpolationMode.CONSOLE_30_60 -> HudQuickMode.SMOOTH_60
            InterpolationMode.STABILIZER_60_CAS -> HudQuickMode.FSR_CAS_ONLY
        }
    }

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
        presetLabel: String,
        context: Context? = null
    ) {
        val prevSpatialScale = DisplayResizeState.spatialCaptureScalePercent(currentLiveConfig.motionScalePercent)
        val merged = mergeThermalState(config, _thermalState.value)
        currentLiveConfig = merged
        activePreferredModeId = preferredModeId
        activePresetLabel = presetLabel
        renderThread?.updateLiveConfig(merged)

        val nextSpatialScale = DisplayResizeState.spatialCaptureScalePercent(merged.motionScalePercent)
        if (prevSpatialScale != nextSpatialScale) {
            FrameCaptureService.onSpatialCaptureScaleChanged()
        }

        if (renderThread != null) {
            val surfaceRate = resolveSurfaceFrameRate(merged)
            context?.let { DisplayRefreshController.applyTargetRefreshRate(it, surfaceRate) }
            FrameBoostOverlayService.updateDisplayRate(preferredModeId, surfaceRate)
        }
    }

    fun onForegroundPackageDetected(context: Context, packageName: String) {
        ensureMonitoringStarted(context)
        _foregroundPackage.value = packageName
        if (!isAutoPilotActive()) return

        val nowMs = System.currentTimeMillis()
        if (!AutoPilotManager.shouldTriggerGameSession(
                packageName = packageName,
                nowMs = nowMs,
                lastActiveGamePackage = lastActiveGamePackage,
                cooldownTimestamps = packageCooldownTimestamps
            )
        ) {
            return
        }

        val customMap = latestSettings.customProfiles.associateBy { it.packageName }
        val captureFps = metricsTracker.latestRollingCaptureFps(System.nanoTime())
        val classified = UniversalGameClassifier.inspectPackage(
            context = context.applicationContext,
            packageName = packageName,
            customProfiles = customMap,
            observedCaptureFps = captureFps
        ) ?: return

        val profile = latestProfile
        val tier = profile?.hardwareTier ?: latestSettings.hardwareTier
        val maxHz = profile?.maxRefreshRateHz ?: latestSettings.targetFps.toFloat().coerceAtLeast(60f)

        val resolved = AutoPilotManager.resolvePresetForPackage(
            packageName = packageName,
            hardwareTier = tier,
            maxRefreshHz = maxHz,
            customProfiles = customMap,
            userHudZones = latestSettings.hudZones,
            isSystemCategorizedGame = classified.isSystemCategorizedGame,
            appLabel = classified.displayName,
            observedCaptureFps = captureFps
        ) ?: return

        lastActiveGamePackage = packageName
        packageCooldownTimestamps[packageName] = nowMs
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
        updateLiveConfig(updatedConfig, preferredModeId, resolved.presetLabel, context)

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
        }
    }

    fun selectQuickModeFromHud(context: Context, quickMode: HudQuickMode) {
        val appContext = context.applicationContext
        val profile = latestProfile
        val allowedFps = profile?.allowedTargetFpsOptions() ?: listOf(60)
        val repo = SettingsRepository(appContext)

        if (quickMode == HudQuickMode.AUTO) {
            scope.launch {
                repo.setAutoPilotEnabled(true)
            }
            return
        }

        val targetMode = when (quickMode) {
            HudQuickMode.BOOST_120_PLUS -> InterpolationMode.COMPETITIVE_60_120
            HudQuickMode.SMOOTH_60 -> InterpolationMode.CONSOLE_30_60
            HudQuickMode.FSR_CAS_ONLY -> InterpolationMode.STABILIZER_60_CAS
            HudQuickMode.AUTO -> InterpolationMode.CONSOLE_30_60
        }
        val desiredFps = when (targetMode) {
            InterpolationMode.COMPETITIVE_60_120 -> allowedFps.lastOrNull() ?: 60
            InterpolationMode.CONSOLE_30_60,
            InterpolationMode.STABILIZER_60_CAS -> 60
        }
        val preferredModeId = profile?.preferredModeIdForTargetFps(desiredFps) ?: 0
        val presetLabel = when (targetMode) {
            InterpolationMode.CONSOLE_30_60 -> "30->60 Console"
            InterpolationMode.COMPETITIVE_60_120 -> "60->$desiredFps Competitive"
            InterpolationMode.STABILIZER_60_CAS -> "60Hz CAS Stabilizer"
        }

        val updatedConfig = currentLiveConfig.copy(
            mode = targetMode,
            targetPanelHz = desiredFps.toFloat(),
            minCaptureFpsGateOverride = null
        )
        updateLiveConfig(updatedConfig, preferredModeId, presetLabel, appContext)

        scope.launch {
            repo.setInterpolationMode(targetMode)
            repo.setTargetFps(desiredFps, preferredModeId)
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
        captureThreadTid: Int = -1,
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
            captureThreadTid = captureThreadTid,
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

        val appContext = context.applicationContext
        val surfaceRate = resolveSurfaceFrameRate(currentLiveConfig)
        DisplayRefreshController.applyTargetRefreshRate(appContext, surfaceRate)
        FrameBoostOverlayService.showOverlays(preferredModeId = preferredModeId, targetHz = surfaceRate)

        oemSuppressorJob = scope.launch(Dispatchers.IO) {
            while (isActive && renderThread != null) {
                OemBoosterSuppressor.suppressCompetingBoosters(
                    context = appContext,
                    activeGamePackage = _foregroundPackage.value
                )
                delay(40_000L)
            }
        }

        metricsTickerJob = scope.launch {
            while (isActive && renderThread != null) {
                val snapshot = metricsTracker.snapshot(System.nanoTime(), activePresetLabel)
                _sessionState.value = PipelineSessionState.Running(snapshot)
                delay(250L)
            }
        }
    }

    @Synchronized
    fun onOverlaySurfaceAvailable(surface: Surface, width: Int, height: Int, densityDpi: Int = 0) {
        pendingSurface = surface
        pendingWidth = width
        pendingHeight = height
        if (densityDpi > 0) {
            pendingDensityDpi = densityDpi
        }
        renderThread?.attachOutputSurface(surface, width, height)
        if (densityDpi > 0) {
            onDisplaySizeChanged(width, height, densityDpi)
        }
    }

    @Synchronized
    fun onOverlaySurfaceDestroyed() {
        pendingSurface = null
        pendingWidth = 0
        pendingHeight = 0
        renderThread?.detachOutputSurface()
    }

    @Synchronized
    fun onDisplaySizeChanged(newWidth: Int, newHeight: Int, densityDpi: Int) {
        pendingWidth = newWidth
        pendingHeight = newHeight
        if (densityDpi > 0) {
            pendingDensityDpi = densityDpi
        }
        val safeDpi = if (densityDpi > 0) densityDpi else pendingDensityDpi.coerceAtLeast(160)
        FrameCaptureService.onDisplaySizeChanged(newWidth, newHeight, safeDpi)
        renderThread?.onDisplaySizeChanged(newWidth, newHeight, safeDpi)
    }

    fun onCapturedContentResized(width: Int, height: Int) {
        val safeDpi = pendingDensityDpi.coerceAtLeast(160)
        renderThread?.onDisplaySizeChanged(width, height, safeDpi)
    }

    fun latestMetricsSnapshot(): PipelineMetricsSnapshot {
        return metricsTracker.snapshot(System.nanoTime(), activePresetLabel)
    }

    fun stopSession(context: Context, errorMessage: String? = null) {
        stopRenderPipelineOnly()
        DisplayRefreshController.restoreOriginalRefreshRate(context)
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
        oemSuppressorJob?.cancel()
        oemSuppressorJob = null
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
