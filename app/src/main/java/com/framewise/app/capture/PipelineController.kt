package com.framewise.app.capture

import android.content.Context
import android.content.Intent
import android.view.Surface
import com.framewise.app.device.InterpolationMode
import com.framewise.app.gpu.GLRenderThread
import com.framewise.app.gpu.LiveRenderConfig
import com.framewise.app.gpu.MetricsTracker
import com.framewise.app.gpu.PipelineMetricsSnapshot
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
    private val metricsTracker = MetricsTracker()

    private val _sessionState = MutableStateFlow<PipelineSessionState>(PipelineSessionState.Idle)
    val sessionState: StateFlow<PipelineSessionState> = _sessionState.asStateFlow()

    @Volatile
    private var renderThread: GLRenderThread? = null

    @Volatile
    private var currentLiveConfig: LiveRenderConfig = LiveRenderConfig()

    @Volatile
    private var activePresetLabel: String = "30->60 Console"

    @Volatile
    private var activePreferredModeId: Int = 0

    private var pendingSurface: Surface? = null
    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0
    private var metricsTickerJob: Job? = null

    @Synchronized
    fun updateLiveConfig(
        config: LiveRenderConfig,
        preferredModeId: Int,
        presetLabel: String
    ) {
        currentLiveConfig = config
        activePreferredModeId = preferredModeId
        activePresetLabel = presetLabel
        renderThread?.updateLiveConfig(config)

        if (renderThread != null) {
            val surfaceRate = resolveSurfaceFrameRate(config)
            FrameBoostOverlayService.updateDisplayRate(preferredModeId, surfaceRate)
        }
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
        stopRenderPipelineOnly()

        activePresetLabel = presetLabel
        activePreferredModeId = preferredModeId
        currentLiveConfig = currentLiveConfig.copy(targetPanelHz = targetHz)
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

    private fun resolveSurfaceFrameRate(config: LiveRenderConfig): Float {
        return when (config.mode) {
            InterpolationMode.COMPETITIVE_60_120 -> config.targetPanelHz.coerceAtLeast(60f)
            InterpolationMode.CONSOLE_30_60,
            InterpolationMode.STABILIZER_60_CAS -> 60f
        }
    }
}
