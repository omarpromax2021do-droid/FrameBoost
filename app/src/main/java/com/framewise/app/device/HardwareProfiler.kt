package com.framewise.app.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class HardwareProfiler(private val context: Context) {

    data class GpuProbeInfo(
        val vendor: String,
        val renderer: String,
        val version: String,
        val majorVersion: Int
    )

    suspend fun collectProfile(lastCalibrationMs: Double?): DeviceHardwareProfile =
        withContext(Dispatchers.Default) {
            val memoryInfo = readMemoryInfo()
            val displayModes = readDisplayModes()
            val maxRefreshHz = displayModes.maxOfOrNull { it.refreshRateHz } ?: 60f
            val gpuInfo = probeGpuInfo()

            val tier = HardwareTierClassifier.classify(
                sdkInt = Build.VERSION.SDK_INT,
                totalRamMb = memoryInfo.first,
                glesMajor = gpuInfo.majorVersion,
                maxRefreshHz = maxRefreshHz,
                avgShaderPassMs = lastCalibrationMs
            )

            DeviceHardwareProfile(
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                socModel = detectSocModel(),
                androidVersion = HardwareTierClassifier.resolveAndroidVersionLabel(
                    sdkInt = Build.VERSION.SDK_INT,
                    releaseOrCodename = Build.VERSION.RELEASE
                ),
                sdkInt = Build.VERSION.SDK_INT,
                glVendor = gpuInfo.vendor,
                glRenderer = gpuInfo.renderer,
                glesVersion = gpuInfo.version,
                glesMajorVersion = gpuInfo.majorVersion,
                totalRamMb = memoryInfo.first,
                availableRamMb = memoryInfo.second,
                supportedDisplayModes = displayModes,
                maxRefreshRateHz = maxRefreshHz,
                hardwareTier = tier,
                lastCalibrationMs = lastCalibrationMs
            )
        }

    suspend fun runThreeSecondGpuCalibration(
        onProgress: (Float) -> Unit
    ): GpuCalibrationResult = withContext(Dispatchers.Default) {
        val displayModes = readDisplayModes()
        val maxRefreshHz = displayModes.maxOfOrNull { it.refreshRateHz } ?: 60f
        val totalRamMb = readMemoryInfo().first

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var programId = 0
        var fboId = 0
        var targetTexId = 0
        var srcTexA = 0
        var srcTexB = 0

        try {
            val egl = createOffscreenEglContext(BENCH_WIDTH, BENCH_HEIGHT)
            eglDisplay = egl.display
            eglContext = egl.context
            eglSurface = egl.surface

            val vendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "Unknown Vendor"
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "Unknown Renderer"
            val glesVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "OpenGL ES 3.0"
            val glesMajor = HardwareTierClassifier.parseGlesMajorVersion(glesVersion)

            programId = compileBenchmarkProgram()
            srcTexA = createNoiseTexture(BENCH_WIDTH, BENCH_HEIGHT, seedOffset = 0)
            srcTexB = createNoiseTexture(BENCH_WIDTH, BENCH_HEIGHT, seedOffset = 7)
            targetTexId = createEmptyTexture(BENCH_WIDTH, BENCH_HEIGHT)
            fboId = createFramebuffer(targetTexId)

            val quadVertices = createFullScreenQuadBuffer()
            val posLoc = GLES30.glGetAttribLocation(programId, "aPosition")
            val texAUniform = GLES30.glGetUniformLocation(programId, "uPrevTex")
            val texBUniform = GLES30.glGetUniformLocation(programId, "uCurrTex")
            val texelSizeUniform = GLES30.glGetUniformLocation(programId, "uTexelSize")

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glViewport(0, 0, BENCH_WIDTH, BENCH_HEIGHT)
            GLES30.glUseProgram(programId)

            GLES30.glEnableVertexAttribArray(posLoc)
            GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 0, quadVertices)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexA)
            GLES30.glUniform1i(texAUniform, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexB)
            GLES30.glUniform1i(texBUniform, 1)
            GLES30.glUniform2f(texelSizeUniform, 1f / BENCH_WIDTH, 1f / BENCH_HEIGHT)

            // Warmup shader pipeline so first-frame compilation jitter does not skew results.
            repeat(4) {
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                GLES30.glFinish()
            }

            val startNs = System.nanoTime()
            val durationNs = 3_000_000_000L
            var totalMeasuredNs = 0L
            var iterations = 0

            while (true) {
                val nowNs = System.nanoTime()
                val elapsedNs = nowNs - startNs
                if (elapsedNs >= durationNs && iterations >= 5) break

                val passStartNs = System.nanoTime()
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                GLES30.glFinish()
                val passEndNs = System.nanoTime()

                totalMeasuredNs += (passEndNs - passStartNs)
                iterations++

                if (iterations % 4 == 0) {
                    onProgress((elapsedNs.toFloat() / durationNs.toFloat()).coerceIn(0f, 1f))
                }
            }

            onProgress(1f)
            val avgPassMs = (totalMeasuredNs.toDouble() / iterations.coerceAtLeast(1)) / 1_000_000.0
            val estimatedMaxFps = HardwareTierClassifier.estimateMaxFps(avgPassMs)
            val tier = HardwareTierClassifier.classify(
                sdkInt = Build.VERSION.SDK_INT,
                totalRamMb = totalRamMb,
                glesMajor = glesMajor,
                maxRefreshHz = maxRefreshHz,
                avgShaderPassMs = avgPassMs
            )
            val preset = HardwareTierClassifier.recommendedPreset(tier, maxRefreshHz)

            GpuCalibrationResult(
                averagePassMs = avgPassMs,
                estimatedMaxFps = estimatedMaxFps,
                glVendor = vendor,
                glRenderer = renderer,
                glesVersion = glesVersion,
                recommendedTier = tier,
                recommendedMode = preset.mode,
                recommendedMotionScalePercent = preset.motionScalePercent,
                recommendedCasSharpness = preset.casSharpness,
                recommendedTargetFps = preset.targetFps
            )
        } finally {
            releaseGlResources(
                eglDisplay = eglDisplay,
                eglContext = eglContext,
                eglSurface = eglSurface,
                programId = programId,
                fboId = fboId,
                textures = intArrayOf(targetTexId, srcTexA, srcTexB)
            )
        }
    }

    private fun readMemoryInfo(): Pair<Long, Long> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L to 0L
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024L * 1024L)
        val availMb = info.availMem / (1024L * 1024L)
        return totalMb to availMb
    }

    private fun readDisplayModes(): List<DisplayModeSpec> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        return display.supportedModes.map { mode ->
            DisplayModeSpec(
                modeId = mode.modeId,
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                refreshRateHz = mode.refreshRate
            )
        }
    }

    private fun detectSocModel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) {
                return "${Build.SOC_MANUFACTURER} $soc".trim()
            }
        }
        val cpuInfoFile = File("/proc/cpuinfo")
        if (cpuInfoFile.canRead()) {
            val lines = runCatching { cpuInfoFile.readLines() }.getOrNull().orEmpty()
            val hardwareLine = lines.firstOrNull { it.startsWith("Hardware", ignoreCase = true) }
            if (hardwareLine != null) {
                return hardwareLine.substringAfter(":").trim()
            }
        }
        return Build.HARDWARE ?: "Unknown SoC"
    }

    private fun probeGpuInfo(): GpuProbeInfo {
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        return try {
            val egl = createOffscreenEglContext(1, 1)
            eglDisplay = egl.display
            eglContext = egl.context
            eglSurface = egl.surface
            val vendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "Unknown"
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "Unknown"
            val version = GLES30.glGetString(GLES30.GL_VERSION) ?: "OpenGL ES 3.0"
            GpuProbeInfo(
                vendor = vendor,
                renderer = renderer,
                version = version,
                majorVersion = HardwareTierClassifier.parseGlesMajorVersion(version)
            )
        } catch (_: Throwable) {
            GpuProbeInfo(
                vendor = "Unavailable",
                renderer = "Unavailable",
                version = "OpenGL ES 0.0",
                majorVersion = 0
            )
        } finally {
            releaseGlResources(eglDisplay, eglContext, eglSurface, 0, 0, intArrayOf())
        }
    }

    private data class EglHandles(
        val display: EGLDisplay,
        val context: EGLContext,
        val surface: EGLSurface
    )

    private fun createOffscreenEglContext(width: Int, height: Int): EglHandles {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(display != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }
        val version = IntArray(2)
        require(EGL14.eglInitialize(display, version, 0, version, 1)) { "Unable to initialize EGL14" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        require(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "No matching EGLConfig found"
        }
        val config = configs[0]!!

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        require(context != EGL14.EGL_NO_CONTEXT) { "Failed to create OpenGL ES 3.0 EGLContext" }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
        require(surface != EGL14.EGL_NO_SURFACE) { "Failed to create EGL Pbuffer surface" }
        require(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        return EglHandles(display, context, surface)
    }

    private fun releaseGlResources(
        eglDisplay: EGLDisplay,
        eglContext: EGLContext,
        eglSurface: EGLSurface,
        programId: Int,
        fboId: Int,
        textures: IntArray
    ) {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (programId != 0) GLES30.glDeleteProgram(programId)
            if (fboId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            val validTextures = textures.filter { it != 0 }.toIntArray()
            if (validTextures.isNotEmpty()) {
                GLES30.glDeleteTextures(validTextures.size, validTextures, 0)
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
    }

    private fun createFullScreenQuadBuffer(): FloatBuffer {
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

    private fun createNoiseTexture(width: Int, height: Int, seedOffset: Int): Int {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = ((x * 3 + y * 5 + seedOffset) and 0xFF).toByte()
                val g = ((x * 7 + y * 2 + seedOffset) and 0xFF).toByte()
                val b = ((x * 11 + y * 13 + seedOffset) and 0xFF).toByte()
                buffer.put(r).put(g).put(b).put(0xFF.toByte())
            }
        }
        buffer.position(0)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        return tex[0]
    }

    private fun createEmptyTexture(width: Int, height: Int): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        return tex[0]
    }

    private fun createFramebuffer(texId: Int): Int {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texId, 0
        )
        return fbo[0]
    }

    private fun compileBenchmarkProgram(): Int {
        val vertexSource = """
            #version 300 es
            in vec2 aPosition;
            out vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        // Exercises texture sampling, diamond motion-gradient search, and 5-tap AMD CAS math.
        val fragmentSource = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uPrevTex;
            uniform sampler2D uCurrTex;
            uniform vec2 uTexelSize;
            out vec4 fragColor;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec3 curr = texture(uCurrTex, vUv).rgb;
                vec3 prev = texture(uPrevTex, vUv).rgb;
                float gx = luma(texture(uCurrTex, vUv + vec2(uTexelSize.x, 0.0)).rgb) -
                           luma(texture(uCurrTex, vUv - vec2(uTexelSize.x, 0.0)).rgb);
                float gy = luma(texture(uCurrTex, vUv + vec2(0.0, uTexelSize.y)).rgb) -
                           luma(texture(uCurrTex, vUv - vec2(0.0, uTexelSize.y)).rgb);
                float dt = luma(curr) - luma(prev);
                vec2 motion = clamp(vec2(gx, gy) * (-dt) * 2.0, vec2(-0.02), vec2(0.02));
                vec3 warpedPrev = texture(uPrevTex, vUv + motion * 0.5).rgb;
                vec3 warpedCurr = texture(uCurrTex, vUv - motion * 0.5).rgb;
                vec3 blended = mix(warpedPrev, warpedCurr, 0.5);

                vec3 n = texture(uCurrTex, vUv + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 s = texture(uCurrTex, vUv + vec2(0.0, uTexelSize.y)).rgb;
                vec3 e = texture(uCurrTex, vUv + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 w = texture(uCurrTex, vUv - vec2(uTexelSize.x, 0.0)).rgb;
                vec3 mn = min(blended, min(min(n, s), min(e, w)));
                vec3 mx = max(blended, max(max(n, s), max(e, w)));
                vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.001)), 0.0, 1.0);
                float weight = -0.15 * sqrt(max(amp.g, 0.0));
                vec3 sharpened = clamp((blended + (n + s + e + w) * weight) / (1.0 + 4.0 * weight), 0.0, 1.0);
                fragColor = vec4(sharpened, 1.0);
            }
        """.trimIndent()

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    private companion object {
        const val BENCH_WIDTH = 720
        const val BENCH_HEIGHT = 480
    }
}
