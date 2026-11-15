package com.framewise.app.gpu

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import com.framewise.app.capture.FrameBoostOverlayService
import com.framewise.app.device.InterpolationMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class LiveRenderConfig(
    val mode: InterpolationMode = InterpolationMode.CONSOLE_30_60,
    val motionScalePercent: Int = 40,
    val casSharpness: Float = 0.65f,
    val hudMaskEnabled: Boolean = true,
    val hudProtectedRects: FloatArray = CasSynthesisShaderProgram.DEFAULT_FPS_HUD_RECTS,
    val smartGateEnabled: Boolean = true,
    val targetPanelHz: Float = 60f,
    val minCaptureFpsGateOverride: Float? = null,
    val thermalScaleCapPercent: Int? = null,
    val thermalForceCasOnly: Boolean = false,
    val thermalWarningBadge: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveRenderConfig) return false
        return mode == other.mode &&
            motionScalePercent == other.motionScalePercent &&
            casSharpness == other.casSharpness &&
            hudMaskEnabled == other.hudMaskEnabled &&
            hudProtectedRects.contentEquals(other.hudProtectedRects) &&
            smartGateEnabled == other.smartGateEnabled &&
            targetPanelHz == other.targetPanelHz &&
            minCaptureFpsGateOverride == other.minCaptureFpsGateOverride &&
            thermalScaleCapPercent == other.thermalScaleCapPercent &&
            thermalForceCasOnly == other.thermalForceCasOnly &&
            thermalWarningBadge == other.thermalWarningBadge
    }

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + motionScalePercent
        result = 31 * result + casSharpness.hashCode()
        result = 31 * result + hudMaskEnabled.hashCode()
        result = 31 * result + hudProtectedRects.contentHashCode()
        result = 31 * result + smartGateEnabled.hashCode()
        result = 31 * result + targetPanelHz.hashCode()
        result = 31 * result + (minCaptureFpsGateOverride?.hashCode() ?: 0)
        result = 31 * result + (thermalScaleCapPercent ?: 0)
        result = 31 * result + thermalForceCasOnly.hashCode()
        result = 31 * result + (thermalWarningBadge?.hashCode() ?: 0)
        return result
    }
}

class GLRenderThread(
    private val context: Context,
    initialWidth: Int,
    initialHeight: Int,
    private val metricsTracker: MetricsTracker,
    initialConfig: LiveRenderConfig,
    private val onInputSurfaceReady: (Surface) -> Unit,
    private val onError: (String) -> Unit
) : HandlerThread("FramewiseGLRenderThread"),
    SurfaceTexture.OnFrameAvailableListener,
    Choreographer.FrameCallback {

    private lateinit var renderHandler: Handler
    private val running = AtomicBoolean(false)
    private val vsyncScheduled = AtomicBoolean(false)
    private val liveConfigRef = AtomicReference(initialConfig)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var rawDisplayWidth: Int = initialWidth.coerceAtLeast(1)
    private var rawDisplayHeight: Int = initialHeight.coerceAtLeast(1)
    private var currentDensityDpi: Int = 160

    private val initialSpec = DisplayResizeState.computeResizeSpec(
        rawWidth = rawDisplayWidth,
        rawHeight = rawDisplayHeight,
        densityDpi = currentDensityDpi,
        motionScalePercent = initialConfig.motionScalePercent
    )
    private var windowWidth: Int = initialSpec.surfaceWidth
    private var windowHeight: Int = initialSpec.surfaceHeight
    private var captureWidth: Int = initialSpec.captureWidth
    private var captureHeight: Int = initialSpec.captureHeight

    private val isResizing = AtomicBoolean(false)
    private var validFramesInCurrentOrientation: Int = 0
    private var lastOverlayVisibleState: Boolean = false
    private var lastCpuSubmissionMs: Double = 0.0

    private var oesTextureId: Int = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val pingPongFbos = PingPongFboPair()
    private val motionFbo = MotionEstimationFbo()
    private val texMatrix = FloatArray(16)
    private var hasCapturedFrame = false

    private var oesProgramId: Int = 0
    private var oesPosLoc: Int = -1
    private var oesTexUniformLoc: Int = -1
    private var oesMatrixUniformLoc: Int = -1

    private var opticalFlowProgram: OpticalFlowShaderProgram? = null
    private var casSynthesisProgram: CasSynthesisShaderProgram? = null
    private val framePacer = FramePacer()
    private val smartGateController = SmartGateController()

    private lateinit var quadBuffer: FloatBuffer

    private var supportsDisjointTimerQuery = false
    private val timerQueryIds = IntArray(2)
    private var activeQueryIndex = 0
    private val queryArmed = BooleanArray(2)
    private var lastMeasuredGpuPassMs: Double = 0.0
    private var renderedFrameCount: Long = 0L

    override fun onLooperPrepared() {
        // Elevate render thread priority so OEM game boosters do not throttle presentation cadence.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        renderHandler = Handler(looper)
        try {
            initEgl()
            initGlResources()
            running.set(true)
            val surface = inputSurface ?: error("Input Surface was not created")
            onInputSurfaceReady(surface)
        } catch (t: Throwable) {
            onError(t.message ?: "GLRenderThread initialization failed")
            cleanupInternal()
            quitSafely()
        }
    }

    fun updateLiveConfig(newConfig: LiveRenderConfig) {
        val prevConfig = liveConfigRef.getAndSet(newConfig)
        val prevSpatial = DisplayResizeState.spatialCaptureScalePercent(prevConfig.motionScalePercent)
        val nextSpatial = DisplayResizeState.spatialCaptureScalePercent(newConfig.motionScalePercent)
        if (prevSpatial != nextSpatial && ::renderHandler.isInitialized) {
            renderHandler.post {
                applyDisplayResizeInternal(rawDisplayWidth, rawDisplayHeight, currentDensityDpi)
            }
        }
    }

    fun attachOutputSurface(surface: Surface, width: Int, height: Int) {
        if (!::renderHandler.isInitialized) return
        renderHandler.post {
            if (!running.get()) return@post
            destroyWindowSurfaceOnly()
            val config = eglConfig ?: return@post
            if (!surface.isValid) return@post

            val attribs = intArrayOf(EGL14.EGL_NONE)
            val created = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, attribs, 0)
            if (created != null && created != EGL14.EGL_NO_SURFACE) {
                windowSurface = created
                val safeW = width.coerceAtLeast(1)
                val safeH = height.coerceAtLeast(1)
                rawDisplayWidth = safeW
                rawDisplayHeight = safeH
                windowWidth = safeW
                windowHeight = safeH
                EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
                if ((safeW > safeH) != (captureWidth > captureHeight)) {
                    applyDisplayResizeInternal(safeW, safeH, currentDensityDpi)
                }
                clearOutputSurfaceTransparent()
                scheduleNextVsyncIfNeeded()
            }
        }
    }

    fun detachOutputSurface() {
        if (!::renderHandler.isInitialized) return
        val latch = CountDownLatch(1)
        val posted = renderHandler.post {
            try {
                updateOverlayVisibility(false)
                destroyWindowSurfaceOnly()
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            latch.await(500, TimeUnit.MILLISECONDS)
        }
    }

    fun onDisplaySizeChanged(newWidth: Int, newHeight: Int, densityDpi: Int = 160) {
        if (!::renderHandler.isInitialized) return
        renderHandler.post {
            applyDisplayResizeInternal(newWidth, newHeight, densityDpi)
        }
    }

    private fun applyDisplayResizeInternal(newWidth: Int, newHeight: Int, densityDpi: Int) {
        if (!running.get()) return
        rawDisplayWidth = newWidth.coerceAtLeast(1)
        rawDisplayHeight = newHeight.coerceAtLeast(1)
        currentDensityDpi = densityDpi.coerceAtLeast(120)

        val spec = DisplayResizeState.computeResizeSpec(
            rawWidth = rawDisplayWidth,
            rawHeight = rawDisplayHeight,
            densityDpi = currentDensityDpi,
            motionScalePercent = liveConfigRef.get().motionScalePercent
        )
        windowWidth = spec.surfaceWidth
        windowHeight = spec.surfaceHeight
        if (spec.captureWidth == captureWidth && spec.captureHeight == captureHeight) {
            return
        }

        isResizing.set(true)
        validFramesInCurrentOrientation = 0
        hasCapturedFrame = false
        framePacer.reset()
        smartGateController.reset()
        updateOverlayVisibility(false)

        captureWidth = spec.captureWidth
        captureHeight = spec.captureHeight
        inputSurfaceTexture?.setDefaultBufferSize(captureWidth, captureHeight)
        pingPongFbos.ensureAllocated(captureWidth, captureHeight)
        motionFbo.ensureAllocated(captureWidth, captureHeight, liveConfigRef.get().motionScalePercent)
        clearFboTransparent(pingPongFbos.prevFboId, pingPongFbos.width, pingPongFbos.height)
        clearFboTransparent(pingPongFbos.currFboId, pingPongFbos.width, pingPongFbos.height)

        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
            clearOutputSurfaceTransparent()
        } else if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        }
        isResizing.set(false)
    }

    fun shutdown() {
        running.set(false)
        vsyncScheduled.set(false)
        if (!::renderHandler.isInitialized) {
            quitSafely()
            return
        }
        val latch = CountDownLatch(1)
        val posted = renderHandler.post {
            try {
                Choreographer.getInstance().removeFrameCallback(this)
                cleanupInternal()
            } finally {
                latch.countDown()
                quitSafely()
            }
        }
        if (posted) {
            latch.await(1, TimeUnit.SECONDS)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (!running.get()) return
        val arrivalNs = System.nanoTime()
        metricsTracker.recordCaptureFrame(arrivalNs)

        try {
            pingPongFbos.ensureAllocated(captureWidth, captureHeight)
            pingPongFbos.swap()

            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)

            if (windowSurface == EGL14.EGL_NO_SURFACE && pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }

            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, pingPongFbos.currFboId)
            GLES31.glViewport(0, 0, pingPongFbos.width, pingPongFbos.height)
            GLES31.glClearColor(0f, 0f, 0f, 0f)
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(oesProgramId)

            GLES30.glEnableVertexAttribArray(oesPosLoc)
            quadBuffer.position(0)
            GLES30.glVertexAttribPointer(oesPosLoc, 2, GLES30.GL_FLOAT, false, 0, quadBuffer)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES30.glUniform1i(oesTexUniformLoc, 0)
            GLES30.glUniformMatrix4fv(oesMatrixUniformLoc, 1, false, texMatrix, 0)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
            GLES31.glViewport(0, 0, windowWidth, windowHeight)

            framePacer.onNewCaptureFrameArrived()
            if (!isResizing.get()) {
                validFramesInCurrentOrientation++
                hasCapturedFrame = true
                scheduleNextVsyncIfNeeded()
            }
        } catch (t: Throwable) {
            onError(t.message ?: "Frame capture GL error")
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        vsyncScheduled.set(false)
        if (!running.get()) return

        if (hasCapturedFrame &&
            windowSurface != EGL14.EGL_NO_SURFACE &&
            pingPongFbos.currTexId != 0 &&
            framePacer.hasPendingWork()
        ) {
            renderPacedFrame(frameTimeNanos)
        } else if (!hasCapturedFrame) {
            updateOverlayVisibility(false)
        }

        if (running.get() && framePacer.hasPendingWork()) {
            scheduleNextVsyncIfNeeded()
        }
    }

    private fun scheduleNextVsyncIfNeeded() {
        if (!running.get() || !hasCapturedFrame || windowSurface == EGL14.EGL_NO_SURFACE) return
        if (!framePacer.hasPendingWork()) return
        if (vsyncScheduled.compareAndSet(false, true)) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun renderPacedFrame(frameTimeNanos: Long) {
        try {
            EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
            syncLiveWindowDimensions()

            val cfg = liveConfigRef.get()
            val nowNs = System.nanoTime()
            val captureFps = metricsTracker.latestRollingCaptureFps(nowNs)
            val cappedRequestedScale = cfg.thermalScaleCapPercent?.let {
                minOf(cfg.motionScalePercent, it)
            } ?: cfg.motionScalePercent
            val gate = smartGateController.evaluate(
                enabled = cfg.smartGateEnabled,
                requestedMode = cfg.mode,
                requestedScalePercent = cappedRequestedScale,
                captureFps = captureFps,
                gpuPassTimeMs = metricsTracker.latestAverageGpuPassMs(),
                nowNs = nowNs,
                minCaptureFpsOverride = cfg.minCaptureFpsGateOverride
            )
            val effectiveBypass = gate.bypassOpticalFlow || cfg.thermalForceCasOnly
            val effectiveScale = if (cfg.thermalScaleCapPercent != null) {
                minOf(gate.effectiveMotionScalePercent, cfg.thermalScaleCapPercent)
            } else {
                gate.effectiveMotionScalePercent
            }
            val decision = framePacer.evaluateVsyncTick(
                frameTimeNs = frameTimeNanos,
                mode = cfg.mode,
                bypassOpticalFlow = effectiveBypass,
                panelRefreshHz = cfg.targetPanelHz
            )

            metricsTracker.updateRuntimeTelemetryState(
                casSharpnessPercent = (cfg.casSharpness * 100f).toInt().coerceIn(0, 100),
                effectiveMotionScalePercent = effectiveScale,
                smartGateStatus = cfg.thermalWarningBadge ?: gate.statusReason,
                thermalBadge = cfg.thermalWarningBadge,
                isInterpolating = decision.outputType == FrameOutputType.SYNTHESIZED_INTERMEDIATE
            )

            val keepTransparent = DisplayResizeState.shouldKeepOverlayTransparent(
                isResizing = isResizing.get(),
                validFramesInCurrentOrientation = validFramesInCurrentOrientation,
                bypassOpticalFlow = effectiveBypass,
                captureFps = captureFps
            )
            if (keepTransparent) {
                updateOverlayVisibility(false)
                clearOutputSurfaceTransparent()
                return
            }

            if (decision.outputType == FrameOutputType.SKIP_TICK) {
                return
            }

            casSynthesisProgram?.prepareOutputFramebuffer(windowWidth, windowHeight)

            renderedFrameCount++
            val vsyncIntervalMs = 1000.0 / cfg.targetPanelHz.coerceAtLeast(30f)
            val gpuDrawStartNs = System.nanoTime()
            val queryIdx = activeQueryIndex
            val measureTimerQuery = supportsDisjointTimerQuery &&
                (renderedFrameCount % TIMER_QUERY_POLL_INTERVAL == 0L)
            if (measureTimerQuery) {
                pollPreviousTimerQuery(1 - queryIdx, vsyncIntervalMs)
                GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, timerQueryIds[queryIdx])
            }

            if (decision.runMotionEstimationPass) {
                motionFbo.ensureAllocated(captureWidth, captureHeight, effectiveScale)
                GLES31.glViewport(0, 0, motionFbo.width, motionFbo.height)
                opticalFlowProgram?.execute(
                    quadBuffer = quadBuffer,
                    prevTexId = pingPongFbos.prevTexId,
                    currTexId = pingPongFbos.currTexId,
                    captureWidth = captureWidth,
                    captureHeight = captureHeight,
                    targetMotionFbo = motionFbo
                )
            } else if (motionFbo.texId == 0) {
                motionFbo.ensureAllocated(captureWidth, captureHeight, effectiveScale)
            }

            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
            GLES31.glViewport(0, 0, windowWidth, windowHeight)
            casSynthesisProgram?.execute(
                quadBuffer = quadBuffer,
                prevTexId = pingPongFbos.prevTexId,
                currTexId = pingPongFbos.currTexId,
                motionTexId = motionFbo.texId,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                motionWidth = motionFbo.width,
                motionHeight = motionFbo.height,
                outputWidth = windowWidth,
                outputHeight = windowHeight,
                interpolationAlpha = decision.interpolationAlpha,
                casSharpness = cfg.casSharpness,
                hudMaskEnabled = cfg.hudMaskEnabled,
                hudProtectedRects = cfg.hudProtectedRects,
                clearBeforeDraw = false
            )

            if (measureTimerQuery) {
                GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
                queryArmed[queryIdx] = true
                activeQueryIndex = 1 - queryIdx
            }
            GLES31.glFlush()
            lastCpuSubmissionMs = (System.nanoTime() - gpuDrawStartNs) / 1_000_000.0
            if (!supportsDisjointTimerQuery) {
                lastMeasuredGpuPassMs = lastCpuSubmissionMs
            }

            EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, decision.presentationTimeNs)
            EGL14.eglSwapBuffers(eglDisplay, windowSurface)
            updateOverlayVisibility(true)

            val swapDoneNs = System.nanoTime()
            metricsTracker.recordOutputFrame(
                swapCompletionNs = swapDoneNs,
                gpuShaderTimeMs = lastMeasuredGpuPassMs,
                captureSourceNs = metricsTracker.lastCaptureTimestampNs()
            )
        } catch (_: Throwable) {
            // Surface teardown during app switch is recovered safely on next capture frame.
        }
    }

    private fun syncLiveWindowDimensions() {
        val wArr = IntArray(1)
        val hArr = IntArray(1)
        val okW = EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_WIDTH, wArr, 0)
        val okH = EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_HEIGHT, hArr, 0)
        if (!okW || !okH || wArr[0] <= 0 || hArr[0] <= 0) return

        val liveW = wArr[0]
        val liveH = hArr[0]
        if (liveW != windowWidth || liveH != windowHeight) {
            windowWidth = liveW
            windowHeight = liveH
            rawDisplayWidth = liveW
            rawDisplayHeight = liveH
            if ((liveW > liveH) != (captureWidth > captureHeight)) {
                applyDisplayResizeInternal(liveW, liveH, currentDensityDpi)
            }
        }
    }

    private fun clearOutputSurfaceTransparent() {
        if (windowSurface == EGL14.EGL_NO_SURFACE) return
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, windowWidth.coerceAtLeast(1), windowHeight.coerceAtLeast(1))
        GLES31.glClearColor(0f, 0f, 0f, 0f)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, windowSurface)
    }

    private fun clearFboTransparent(fboId: Int, width: Int, height: Int) {
        if (fboId == 0 || width <= 0 || height <= 0) return
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fboId)
        GLES31.glViewport(0, 0, width, height)
        GLES31.glClearColor(0f, 0f, 0f, 0f)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
    }

    private fun updateOverlayVisibility(visible: Boolean) {
        if (lastOverlayVisibleState == visible) return
        lastOverlayVisibleState = visible
        FrameBoostOverlayService.setOverlayRenderVisible(visible)
    }

    private fun pollPreviousTimerQuery(prevIndex: Int, vsyncIntervalMs: Double) {
        if (!queryArmed[prevIndex]) return
        val available = IntArray(1)
        GLES30.glGetQueryObjectuiv(timerQueryIds[prevIndex], GLES30.GL_QUERY_RESULT_AVAILABLE, available, 0)
        if (available[0] != 0) {
            val disjoint = IntArray(1)
            GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, disjoint, 0)
            val elapsedNs = IntArray(1)
            GLES30.glGetQueryObjectuiv(timerQueryIds[prevIndex], GLES30.GL_QUERY_RESULT, elapsedNs, 0)
            val unsignedNs = elapsedNs[0].toLong() and 0xFFFFFFFFL
            val queryMs = unsignedNs / 1_000_000.0
            lastMeasuredGpuPassMs = if (disjoint[0] == 0 && queryMs in 0.0..vsyncIntervalMs) {
                queryMs
            } else {
                lastCpuSubmissionMs
            }
            queryArmed[prevIndex] = false
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }

        val version = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Unable to initialize EGL14" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val ok = EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        if (!ok || numConfigs[0] <= 0) {
            configAttribs[1] = EGL14.EGL_OPENGL_ES2_BIT
            require(
                EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0
            ) { "No suitable EGLConfig found" }
        }
        eglConfig = configs[0]!!

        val es31Attribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_CONTEXT_MINOR_VERSION_KHR, 1,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, es31Attribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            val es30Attribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, es30Attribs, 0)
        }
        require(eglContext != EGL14.EGL_NO_CONTEXT) { "Failed to create OpenGL ES 3.x context" }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        require(pbufferSurface != EGL14.EGL_NO_SURFACE) { "Failed to create initial Pbuffer surface" }
        require(EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun initGlResources() {
        quadBuffer = createQuadBuffer()
        pingPongFbos.ensureAllocated(captureWidth, captureHeight)
        motionFbo.ensureAllocated(captureWidth, captureHeight, liveConfigRef.get().motionScalePercent)

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        oesTextureId = texIds[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        inputSurfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(captureWidth, captureHeight)
            setOnFrameAvailableListener(this@GLRenderThread, renderHandler)
        }
        inputSurface = Surface(inputSurfaceTexture)

        oesProgramId = buildOesToFboProgram()
        oesPosLoc = GLES30.glGetAttribLocation(oesProgramId, "aPosition")
        oesTexUniformLoc = GLES30.glGetUniformLocation(oesProgramId, "uExternalTex")
        oesMatrixUniformLoc = GLES30.glGetUniformLocation(oesProgramId, "uTexMatrix")

        opticalFlowProgram = OpticalFlowShaderProgram(context)
        casSynthesisProgram = CasSynthesisShaderProgram(context)

        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        supportsDisjointTimerQuery = extensions.contains("GL_EXT_disjoint_timer_query")
        if (supportsDisjointTimerQuery) {
            GLES30.glGenQueries(2, timerQueryIds, 0)
        }
    }

    private fun destroyWindowSurfaceOnly() {
        if (windowSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            } else {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            EGL14.eglDestroySurface(eglDisplay, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun cleanupInternal() {
        framePacer.reset()
        smartGateController.reset()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }
            if (supportsDisjointTimerQuery) {
                GLES30.glDeleteQueries(2, timerQueryIds, 0)
            }
            if (oesProgramId != 0) GLES30.glDeleteProgram(oesProgramId)
            opticalFlowProgram?.release()
            opticalFlowProgram = null
            casSynthesisProgram?.release()
            casSynthesisProgram = null
            if (oesTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            motionFbo.release()
            pingPongFbos.release()

            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }

        inputSurface?.release()
        inputSurface = null
        inputSurfaceTexture?.release()
        inputSurfaceTexture = null

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        pbufferSurface = EGL14.EGL_NO_SURFACE
        windowSurface = EGL14.EGL_NO_SURFACE
    }

    private fun buildOesToFboProgram(): Int {
        val essl3Vertex = """
            #version 300 es
            in vec2 aPosition;
            uniform mat4 uTexMatrix;
            out vec2 vTexCoord;
            void main() {
                vec2 uv = aPosition * 0.5 + 0.5;
                vTexCoord = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val essl3Fragment = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES uExternalTex;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uExternalTex, vTexCoord);
            }
        """.trimIndent()

        val essl3Program = ShaderUtils.tryCompileProgram(essl3Vertex, essl3Fragment)
        if (essl3Program != 0) return essl3Program

        val essl1Vertex = """
            attribute vec2 aPosition;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                vec2 uv = aPosition * 0.5 + 0.5;
                vTexCoord = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val essl1Fragment = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uExternalTex;
            void main() {
                gl_FragColor = texture2D(uExternalTex, vTexCoord);
            }
        """.trimIndent()

        val fallbackProgram = ShaderUtils.tryCompileProgram(essl1Vertex, essl1Fragment)
        require(fallbackProgram != 0) { "Failed to compile external OES shader program" }
        return fallbackProgram
    }

    private fun createQuadBuffer(): FloatBuffer {
        val coords = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        return ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(coords)
                position(0)
            }
    }

    private companion object {
        const val EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
        const val TIMER_QUERY_POLL_INTERVAL = 20L
    }
}
