package com.framewise.app.benchmark

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.framewise.app.device.InterpolationMode
import com.framewise.app.gpu.CasSynthesisShaderProgram
import com.framewise.app.gpu.MotionEstimationFbo
import com.framewise.app.gpu.OpticalFlowShaderProgram
import com.framewise.app.gpu.PingPongFboPair
import com.framewise.app.gpu.ShaderUtils
import com.google.gson.GsonBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class BenchmarkSnapshot(
    val mode: InterpolationMode = InterpolationMode.COMPETITIVE_60_120,
    val baseRateHz: Float = 0f,
    val outputFps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val gpuShaderTimeMs: Float = 0f,
    val casSharpness: Float = 0.65f,
    val motionScalePercent: Int = 40,
    val hudMaskEnabled: Boolean = true,
    val glRenderer: String = "",
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0
) {
    fun toMarkdownReport(appName: String, versionName: String, deviceModel: String): String {
        return buildString {
            appendLine("# $appName Split-Screen Benchmark Report")
            appendLine("- **Version**: v$versionName")
            appendLine("- **Device**: $deviceModel")
            appendLine("- **GPU Renderer**: $glRenderer")
            appendLine("- **Viewport**: ${viewportWidth}x$viewportHeight")
            appendLine("- **Mode**: $mode")
            appendLine("- **Base Baseline (Top Half)**: ${String.format(Locale.US, "%.1f", baseRateHz)} FPS (No CAS)")
            appendLine("- **Framewise Output (Bottom Half)**: ${String.format(Locale.US, "%.1f", outputFps)} FPS")
            appendLine("- **Frame Time**: ${String.format(Locale.US, "%.2f", frameTimeMs)} ms")
            appendLine("- **GPU Shader Pass (Optical Flow + CAS)**: ${String.format(Locale.US, "%.2f", gpuShaderTimeMs)} ms")
            appendLine("- **Motion Scale**: $motionScalePercent%")
            appendLine("- **CAS Sharpness**: ${String.format(Locale.US, "%.2f", casSharpness)}")
            appendLine("- **HUD Mask**: $hudMaskEnabled")
        }
    }

    fun toJsonReport(deviceModel: String, versionName: String): String {
        val payload = linkedMapOf<String, Any>(
            "appVersion" to versionName,
            "deviceModel" to deviceModel,
            "glRenderer" to glRenderer,
            "viewport" to "${viewportWidth}x$viewportHeight",
            "mode" to mode.name,
            "baseBaselineFps" to baseRateHz,
            "framewiseOutputFps" to outputFps,
            "frameTimeMs" to frameTimeMs,
            "gpuShaderTimeMs" to gpuShaderTimeMs,
            "motionScalePercent" to motionScalePercent,
            "casSharpness" to casSharpness,
            "hudMaskEnabled" to hudMaskEnabled
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(payload)
    }
}

class BenchmarkRenderer(
    private val context: Context,
    private val hudProtectedRectsProvider: () -> FloatArray,
    private val onSnapshotUpdated: (BenchmarkSnapshot) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    var mode: InterpolationMode = InterpolationMode.COMPETITIVE_60_120

    @Volatile
    var casSharpness: Float = 0.65f

    @Volatile
    var motionScalePercent: Int = 40

    @Volatile
    var hudMaskEnabled: Boolean = true

    @Volatile
    var panelMaxHz: Float = 60f

    private val pingPongFbos = PingPongFboPair()
    private val motionFbo = MotionEstimationFbo()
    private var opticalFlowProgram: OpticalFlowShaderProgram? = null
    private var casSynthesisProgram: CasSynthesisShaderProgram? = null
    private var sceneProgramId: Int = 0
    private var scenePosLoc: Int = -1
    private var sceneTimeLoc: Int = -1
    private var sceneResLoc: Int = -1

    private lateinit var quadBuffer: FloatBuffer
    private var viewWidth: Int = 1080
    private var viewHeight: Int = 720
    private var glRendererName: String = "OpenGL ES"

    private var supportsTimerQuery = false
    private val timerQueries = IntArray(2)
    private var queryIndex = 0
    private val queryArmed = BooleanArray(2)
    private var lastGpuPassMs = 0.0

    private var startTimeNs = 0L
    private var lastSceneRenderNs = 0L
    private var lastTickNs = 0L
    private var showingSynthesizedTick = false
    private var baseFrameCount = 0L
    private var lastMetricsEmitNs = 0L

    private val baseTimestampsNs = ArrayDeque<Long>()
    private val outputTimestampsNs = ArrayDeque<Long>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quadBuffer = createQuadBuffer()
        glRendererName = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty().ifBlank { "OpenGL ES GPU" }
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        supportsTimerQuery = extensions.contains("GL_EXT_disjoint_timer_query")
        if (supportsTimerQuery) {
            GLES30.glGenQueries(2, timerQueries, 0)
        }

        sceneProgramId = buildSceneProgram()
        scenePosLoc = GLES30.glGetAttribLocation(sceneProgramId, "aPosition")
        sceneTimeLoc = GLES30.glGetUniformLocation(sceneProgramId, "uTime")
        sceneResLoc = GLES30.glGetUniformLocation(sceneProgramId, "uResolution")

        opticalFlowProgram = OpticalFlowShaderProgram(context)
        casSynthesisProgram = CasSynthesisShaderProgram(context)

        startTimeNs = System.nanoTime()
        lastSceneRenderNs = 0L
        lastTickNs = 0L
        baseFrameCount = 0L
        showingSynthesizedTick = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(64)
        viewHeight = height.coerceAtLeast(64)
        val sceneW = (viewWidth / 2).coerceIn(320, 1080)
        val sceneH = (viewHeight / 2).coerceIn(240, 1080)
        pingPongFbos.ensureAllocated(sceneW, sceneH)
        motionFbo.ensureAllocated(sceneW, sceneH, motionScalePercent)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNs = System.nanoTime()
        val sceneW = pingPongFbos.width.coerceAtLeast(320)
        val sceneH = pingPongFbos.height.coerceAtLeast(240)
        pingPongFbos.ensureAllocated(sceneW, sceneH)

        val currentMode = mode
        val targetBaseIntervalNs = when (currentMode) {
            InterpolationMode.CONSOLE_30_60 -> 33_333_333L
            InterpolationMode.COMPETITIVE_60_120 -> if (panelMaxHz >= 88f) 16_666_667L else 33_333_333L
            InterpolationMode.STABILIZER_60_CAS -> 16_666_667L
        }

        val elapsedSec = (nowNs - startTimeNs) / 1_000_000_000.0f
        var newBaseFrameGenerated = false
        if (lastSceneRenderNs == 0L || nowNs - lastSceneRenderNs >= targetBaseIntervalNs) {
            renderTestPatternIntoNextFbo(elapsedSec, sceneW, sceneH)
            lastSceneRenderNs = nowNs
            baseFrameCount++
            newBaseFrameGenerated = true
            recordTimestamp(baseTimestampsNs, nowNs)
        }

        val hasTwoFrames = baseFrameCount >= 2L
        val shouldSynthesize = hasTwoFrames &&
            currentMode != InterpolationMode.STABILIZER_60_CAS &&
            newBaseFrameGenerated

        if (shouldSynthesize) {
            showingSynthesizedTick = true
        } else {
            showingSynthesizedTick = false
        }

        val gpuStartNs = if (!supportsTimerQuery) System.nanoTime() else 0L
        val qIdx = queryIndex
        if (supportsTimerQuery) {
            pollTimerQuery(1 - qIdx)
            GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, timerQueries[qIdx])
        }

        val topHalfTexId = if (showingSynthesizedTick && hasTwoFrames) {
            pingPongFbos.prevTexId
        } else {
            pingPongFbos.currTexId
        }
        val activeScale = motionScalePercent.coerceIn(25, 60)
        motionFbo.ensureAllocated(sceneW, sceneH, activeScale)

        if (showingSynthesizedTick) {
            opticalFlowProgram?.execute(
                quadBuffer = quadBuffer,
                prevTexId = pingPongFbos.prevTexId,
                currTexId = pingPongFbos.currTexId,
                captureWidth = sceneW,
                captureHeight = sceneH,
                targetMotionFbo = motionFbo
            )
        }

        val halfY = viewHeight / 2
        val hudRects = hudProtectedRectsProvider()

        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)

        // Top half: Raw unsharpened baseline at base FPS
        GLES30.glScissor(0, halfY, viewWidth, viewHeight - halfY)
        casSynthesisProgram?.execute(
            quadBuffer = quadBuffer,
            prevTexId = topHalfTexId,
            currTexId = topHalfTexId,
            motionTexId = motionFbo.texId,
            captureWidth = sceneW,
            captureHeight = sceneH,
            motionWidth = motionFbo.width,
            motionHeight = motionFbo.height,
            outputWidth = viewWidth,
            outputHeight = viewHeight,
            interpolationAlpha = 0f,
            casSharpness = 0f,
            hudMaskEnabled = false,
            hudProtectedRects = hudRects
        )

        // Bottom half: Framewise Optical Flow + AMD CAS sharpened output
        GLES30.glScissor(0, 0, viewWidth, halfY)
        casSynthesisProgram?.execute(
            quadBuffer = quadBuffer,
            prevTexId = pingPongFbos.prevTexId,
            currTexId = pingPongFbos.currTexId,
            motionTexId = motionFbo.texId,
            captureWidth = sceneW,
            captureHeight = sceneH,
            motionWidth = motionFbo.width,
            motionHeight = motionFbo.height,
            outputWidth = viewWidth,
            outputHeight = viewHeight,
            interpolationAlpha = if (showingSynthesizedTick) 0.5f else 0f,
            casSharpness = casSharpness.coerceIn(0f, 1f),
            hudMaskEnabled = hudMaskEnabled,
            hudProtectedRects = hudRects
        )

        // Crisp neon cyan split divider line
        GLES30.glScissor(0, (halfY - 2).coerceAtLeast(0), viewWidth, 4)
        GLES30.glClearColor(0f, 0.90f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)

        if (supportsTimerQuery) {
            GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
            queryArmed[qIdx] = true
            queryIndex = 1 - qIdx
        } else {
            GLES30.glFinish()
            lastGpuPassMs = (System.nanoTime() - gpuStartNs) / 1_000_000.0
        }

        val endNs = System.nanoTime()
        val frameTimeMs = if (lastTickNs > 0L && endNs > lastTickNs) {
            ((endNs - lastTickNs) / 1_000_000.0).toFloat()
        } else {
            0f
        }
        lastTickNs = endNs
        recordTimestamp(outputTimestampsNs, endNs)

        if (nowNs - lastMetricsEmitNs >= 250_000_000L) {
            lastMetricsEmitNs = nowNs
            onSnapshotUpdated(
                BenchmarkSnapshot(
                    mode = currentMode,
                    baseRateHz = computeFps(baseTimestampsNs, nowNs),
                    outputFps = computeFps(outputTimestampsNs, nowNs),
                    frameTimeMs = frameTimeMs,
                    gpuShaderTimeMs = lastGpuPassMs.toFloat(),
                    casSharpness = casSharpness,
                    motionScalePercent = activeScale,
                    hudMaskEnabled = hudMaskEnabled,
                    glRenderer = glRendererName,
                    viewportWidth = viewWidth,
                    viewportHeight = viewHeight
                )
            )
        }
    }

    fun release() {
        opticalFlowProgram?.release()
        opticalFlowProgram = null
        casSynthesisProgram?.release()
        casSynthesisProgram = null
        if (sceneProgramId != 0) {
            GLES30.glDeleteProgram(sceneProgramId)
            sceneProgramId = 0
        }
        if (supportsTimerQuery) {
            GLES30.glDeleteQueries(2, timerQueries, 0)
        }
        motionFbo.release()
        pingPongFbos.release()
    }

    private fun renderTestPatternIntoNextFbo(elapsedSec: Float, w: Int, h: Int) {
        pingPongFbos.swap()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pingPongFbos.currFboId)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(sceneProgramId)
        GLES30.glEnableVertexAttribArray(scenePosLoc)
        quadBuffer.position(0)
        GLES30.glVertexAttribPointer(scenePosLoc, 2, GLES30.GL_FLOAT, false, 0, quadBuffer)
        GLES30.glUniform1f(sceneTimeLoc, elapsedSec)
        GLES30.glUniform2f(sceneResLoc, w.toFloat(), h.toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun pollTimerQuery(prevIndex: Int) {
        if (!queryArmed[prevIndex]) return
        val available = IntArray(1)
        GLES30.glGetQueryObjectuiv(timerQueries[prevIndex], GLES30.GL_QUERY_RESULT_AVAILABLE, available, 0)
        if (available[0] != 0) {
            val elapsedNs = IntArray(1)
            GLES30.glGetQueryObjectuiv(timerQueries[prevIndex], GLES30.GL_QUERY_RESULT, elapsedNs, 0)
            lastGpuPassMs = (elapsedNs[0].toLong() and 0xFFFFFFFFL) / 1_000_000.0
            queryArmed[prevIndex] = false
        }
    }

    private fun recordTimestamp(queue: ArrayDeque<Long>, nowNs: Long) {
        val cutoff = nowNs - 1_000_000_000L
        while (queue.isNotEmpty() && queue.first() < cutoff) {
            queue.removeFirst()
        }
        queue.addLast(nowNs)
    }

    private fun computeFps(queue: ArrayDeque<Long>, nowNs: Long): Float {
        val cutoff = nowNs - 1_000_000_000L
        while (queue.isNotEmpty() && queue.first() < cutoff) {
            queue.removeFirst()
        }
        if (queue.size < 2) return 0f
        val spanNs = queue.last() - queue.first()
        if (spanNs <= 0L) return 0f
        return ((queue.size - 1) * 1_000_000_000.0 / spanNs).toFloat()
    }

    private fun buildSceneProgram(): Int {
        val vert = """
            #version 300 es
            in vec2 aPosition;
            out vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        // High-detail procedural scene designed to show both motion smoothness and CAS texture crispness.
        val frag = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            uniform float uTime;
            uniform vec2 uResolution;
            out vec4 fragColor;

            void main() {
                // Mirror vertically per half so Top and Bottom display identical motion content
                vec2 localUv = vec2(vUv.x, fract(vUv.y * 2.0));
                float scroll = uTime * 0.42;

                // Soft-filtered perspective cyber grid + fine checkerboard (makes CAS sharpening obvious)
                float depth = 0.25 + localUv.y * 1.1;
                vec2 gridCoord = vec2((localUv.x - 0.5) / depth * 7.0 + scroll * 5.0, (1.0 / depth) * 2.4 + scroll * 3.0);
                vec2 grid = abs(fract(gridCoord) - 0.5);
                float lineMask = 1.0 - smoothstep(0.03, 0.14, min(grid.x, grid.y));

                // Fine micro-detail surface modulation to mimic soft upscaled 3D terrain
                float detail = 0.5 + 0.5 * sin(gridCoord.x * 12.0) * cos(gridCoord.y * 12.0);
                vec3 bg = mix(vec3(0.04, 0.07, 0.12), vec3(0.11, 0.22, 0.34), lineMask * 0.8 + detail * 0.28);

                // Moving strafing target block (smooth motion test)
                float targetX = fract(uTime * 0.55) * 1.2 - 0.1;
                vec2 dTarget = localUv - vec2(targetX, 0.52);
                float boxDist = max(abs(dTarget.x) * 1.4, abs(dTarget.y));
                if (boxDist < 0.14) {
                    float rings = 0.5 + 0.5 * sin(boxDist * 140.0);
                    bg = mix(vec3(0.95, 0.42, 0.15), vec3(0.05, 0.95, 0.65), rings);
                }

                // Secondary counter-moving orb
                float orbX = 1.0 - fract(uTime * 0.38);
                vec2 dOrb = (localUv - vec2(orbX, 0.30)) * vec2(uResolution.x / max(uResolution.y * 0.5, 1.0), 1.0);
                float orbDist = length(dOrb);
                if (orbDist < 0.11) {
                    float rim = smoothstep(0.11, 0.05, orbDist);
                    bg = mix(bg, vec3(0.0, 0.88, 1.0), rim);
                }

                // Static HUD reticle & box corner test marker
                vec2 dHud = abs(localUv - vec2(0.5, 0.5));
                if ((dHud.x < 0.035 && dHud.y < 0.005) || (dHud.y < 0.06 && dHud.x < 0.003)) {
                    bg = vec3(1.0, 1.0, 1.0);
                }

                fragColor = vec4(clamp(bg, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        val prog = ShaderUtils.tryCompileProgram(vert, frag)
        require(prog != 0) { "Failed to compile Benchmark scene shader" }
        return prog
    }

    private fun createQuadBuffer(): FloatBuffer {
        val coords = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        return ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(coords)
                position(0)
            }
    }

    private companion object {
        const val GL_TIME_ELAPSED_EXT = 0x88BF
    }
}
