package com.framewise.app.gpu

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GLRenderThread(
    private val initialWidth: Int,
    private val initialHeight: Int,
    private val metricsTracker: MetricsTracker,
    private val activePresetLabel: String,
    private val onInputSurfaceReady: (Surface) -> Unit,
    private val onError: (String) -> Unit
) : HandlerThread("FramewiseGLRenderThread"),
    SurfaceTexture.OnFrameAvailableListener,
    Choreographer.FrameCallback {

    private lateinit var renderHandler: Handler
    private val running = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowWidth: Int = initialWidth.coerceAtLeast(1)
    private var windowHeight: Int = initialHeight.coerceAtLeast(1)

    private var captureWidth: Int = initialWidth.coerceAtLeast(16)
    private var captureHeight: Int = initialHeight.coerceAtLeast(16)

    private var oesTextureId: Int = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val pingPongFbos = PingPongFboPair()
    private val texMatrix = FloatArray(16)
    private var hasCapturedFrame = false

    private var oesProgramId: Int = 0
    private var oesPosLoc: Int = -1
    private var oesTexUniformLoc: Int = -1
    private var oesMatrixUniformLoc: Int = -1

    private var presentProgramId: Int = 0
    private var presentPosLoc: Int = -1
    private var presentTexUniformLoc: Int = -1

    private lateinit var quadBuffer: FloatBuffer

    private var supportsDisjointTimerQuery = false
    private val timerQueryIds = IntArray(2)
    private var activeQueryIndex = 0
    private var queryArmed = BooleanArray(2)
    private var lastMeasuredGpuPassMs: Double = 0.0

    override fun onLooperPrepared() {
        renderHandler = Handler(looper)
        try {
            initEgl()
            initGlResources()
            running.set(true)
            val surface = inputSurface ?: error("Input Surface was not created")
            onInputSurfaceReady(surface)
            Choreographer.getInstance().postFrameCallback(this)
        } catch (t: Throwable) {
            onError(t.message ?: "GLRenderThread initialization failed")
            cleanupInternal()
            quitSafely()
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
                windowWidth = width.coerceAtLeast(1)
                windowHeight = height.coerceAtLeast(1)
                EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
            }
        }
    }

    fun detachOutputSurface() {
        if (!::renderHandler.isInitialized) return
        val latch = CountDownLatch(1)
        val posted = renderHandler.post {
            try {
                destroyWindowSurfaceOnly()
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            latch.await(500, TimeUnit.MILLISECONDS)
        }
    }

    fun updateCaptureSize(width: Int, height: Int) {
        if (!::renderHandler.isInitialized) return
        renderHandler.post {
            if (!running.get()) return@post
            val safeW = width.coerceAtLeast(16)
            val safeH = height.coerceAtLeast(16)
            if (safeW != captureWidth || safeH != captureHeight) {
                captureWidth = safeW
                captureHeight = safeH
                inputSurfaceTexture?.setDefaultBufferSize(safeW, safeH)
                pingPongFbos.ensureAllocated(safeW, safeH)
            }
        }
    }

    fun shutdown() {
        running.set(false)
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

            // Ensure an active GL context before offscreen OES->FBO draw even if windowSurface is detached.
            if (windowSurface == EGL14.EGL_NO_SURFACE && pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingPongFbos.currFboId)
            GLES30.glViewport(0, 0, pingPongFbos.width, pingPongFbos.height)
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
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            hasCapturedFrame = true
        } catch (t: Throwable) {
            onError(t.message ?: "Frame capture GL error")
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running.get()) return

        if (hasCapturedFrame && windowSurface != EGL14.EGL_NO_SURFACE && pingPongFbos.currTexId != 0) {
            renderCurrFrameToOverlay(frameTimeNanos)
        }

        if (running.get()) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun renderCurrFrameToOverlay(frameTimeNanos: Long) {
        try {
            EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)

            val gpuStartNs = if (!supportsDisjointTimerQuery) System.nanoTime() else 0L
            val queryIdx = activeQueryIndex
            if (supportsDisjointTimerQuery) {
                pollPreviousTimerQuery(1 - queryIdx)
                GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, timerQueryIds[queryIdx])
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, windowWidth, windowHeight)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glUseProgram(presentProgramId)
            GLES30.glEnableVertexAttribArray(presentPosLoc)
            quadBuffer.position(0)
            GLES30.glVertexAttribPointer(presentPosLoc, 2, GLES30.GL_FLOAT, false, 0, quadBuffer)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pingPongFbos.currTexId)
            GLES30.glUniform1i(presentTexUniformLoc, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

            if (supportsDisjointTimerQuery) {
                GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
                queryArmed[queryIdx] = true
                activeQueryIndex = 1 - queryIdx
            } else {
                GLES30.glFinish()
                lastMeasuredGpuPassMs = (System.nanoTime() - gpuStartNs) / 1_000_000.0
            }

            EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, frameTimeNanos)
            EGL14.eglSwapBuffers(eglDisplay, windowSurface)

            val swapDoneNs = System.nanoTime()
            metricsTracker.recordOutputFrame(
                swapCompletionNs = swapDoneNs,
                gpuShaderTimeMs = lastMeasuredGpuPassMs,
                captureSourceNs = metricsTracker.lastCaptureTimestampNs()
            )
        } catch (_: Throwable) {
            // Transient surface teardown during app switch; handled safely on next VSync.
        }
    }

    private fun pollPreviousTimerQuery(prevIndex: Int) {
        if (!queryArmed[prevIndex]) return
        val available = IntArray(1)
        GLES30.glGetQueryObjectuiv(timerQueryIds[prevIndex], GLES30.GL_QUERY_RESULT_AVAILABLE, available, 0)
        if (available[0] != 0) {
            val elapsedNs = IntArray(1)
            GLES30.glGetQueryObjectuiv(timerQueryIds[prevIndex], GLES30.GL_QUERY_RESULT, elapsedNs, 0)
            val unsignedNs = elapsedNs[0].toLong() and 0xFFFFFFFFL
            lastMeasuredGpuPassMs = unsignedNs / 1_000_000.0
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
            // Fallback to ES2 bit when ES3 bit isn't explicitly advertised in EGLConfig.
            configAttribs[1] = EGL14.EGL_OPENGL_ES2_BIT
            require(
                EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0
            ) { "No suitable EGLConfig found" }
        }
        eglConfig = configs[0]!!

        // Try OpenGL ES 3.1 context first, fallback to ES 3.0 if minor version attr is unsupported.
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

        presentProgramId = buildPresentProgram()
        presentPosLoc = GLES30.glGetAttribLocation(presentProgramId, "aPosition")
        presentTexUniformLoc = GLES30.glGetUniformLocation(presentProgramId, "uFrameTex")

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
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }
            if (supportsDisjointTimerQuery) {
                GLES30.glDeleteQueries(2, timerQueryIds, 0)
            }
            if (oesProgramId != 0) GLES30.glDeleteProgram(oesProgramId)
            if (presentProgramId != 0) GLES30.glDeleteProgram(presentProgramId)
            if (oesTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
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

        val essl3Program = tryCompileProgram(essl3Vertex, essl3Fragment)
        if (essl3Program != 0) return essl3Program

        // Compatibility fallback for devices supporting only ESSL1 external OES sampler.
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

        val fallbackProgram = tryCompileProgram(essl1Vertex, essl1Fragment)
        require(fallbackProgram != 0) { "Failed to compile external OES shader program" }
        return fallbackProgram
    }

    private fun buildPresentProgram(): Int {
        val vertex = """
            #version 300 es
            in vec2 aPosition;
            out vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val fragment = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uFrameTex;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uFrameTex, vUv);
            }
        """.trimIndent()

        val program = tryCompileProgram(vertex, fragment)
        require(program != 0) { "Failed to compile 1x passthrough shader program" }
        return program
    }

    private fun tryCompileProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES30.glDeleteShader(vs)
            return 0
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
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
    }
}
