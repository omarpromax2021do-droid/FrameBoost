package com.framewise.app.gpu

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLES31
import com.framewise.app.R
import java.nio.FloatBuffer

class CasSynthesisShaderProgram(context: Context) {
    private val programId: Int = ShaderUtils.buildQuadProgramFromRaw(context, R.raw.frame_synthesis_cas)
    private val posLoc: Int = GLES30.glGetAttribLocation(programId, "aPosition")
    private val prevTexLoc: Int = GLES30.glGetUniformLocation(programId, "uPrevTex")
    private val currTexLoc: Int = GLES30.glGetUniformLocation(programId, "uCurrTex")
    private val motionTexLoc: Int = GLES30.glGetUniformLocation(programId, "uMotionTex")
    private val texelSizeLoc: Int = GLES30.glGetUniformLocation(programId, "uTexelSize")
    private val outputTexelSizeLoc: Int = GLES30.glGetUniformLocation(programId, "uOutputTexelSize")
    private val motionTexelSizeLoc: Int = GLES30.glGetUniformLocation(programId, "uMotionTexelSize")
    private val alphaLoc: Int = GLES30.glGetUniformLocation(programId, "uInterpolationAlpha")
    private val casLoc: Int = GLES30.glGetUniformLocation(programId, "uCasSharpness")
    private val fsrUpscaleActiveLoc: Int = GLES30.glGetUniformLocation(programId, "uFsrUpscaleActive")
    private val hudMaskEnabledLoc: Int = GLES30.glGetUniformLocation(programId, "uHudMaskEnabled")
    private val hudRectsLoc: Int = GLES30.glGetUniformLocation(programId, "uHudProtectedRects")

    // Clear default window FBO before GPU draw timing so dequeueBuffer stalls do not pollute shader metrics.
    fun prepareOutputFramebuffer(outputWidth: Int, outputHeight: Int) {
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))
        GLES31.glClearColor(0f, 0f, 0f, 0f)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
    }

    fun execute(
        quadBuffer: FloatBuffer,
        prevTexId: Int,
        currTexId: Int,
        motionTexId: Int,
        captureWidth: Int,
        captureHeight: Int,
        motionWidth: Int,
        motionHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        interpolationAlpha: Float,
        casSharpness: Float,
        hudMaskEnabled: Boolean,
        hudProtectedRects: FloatArray = DEFAULT_FPS_HUD_RECTS,
        clearBeforeDraw: Boolean = true
    ) {
        val safeOutW = outputWidth.coerceAtLeast(1)
        val safeOutH = outputHeight.coerceAtLeast(1)
        val safeCapW = captureWidth.coerceAtLeast(1)
        val safeCapH = captureHeight.coerceAtLeast(1)

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, safeOutW, safeOutH)
        if (clearBeforeDraw) {
            GLES31.glClearColor(0f, 0f, 0f, 0f)
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
        }

        GLES30.glUseProgram(programId)
        GLES30.glEnableVertexAttribArray(posLoc)
        quadBuffer.position(0)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 0, quadBuffer)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTexId)
        GLES30.glUniform1i(prevTexLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currTexId)
        GLES30.glUniform1i(currTexLoc, 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, motionTexId)
        GLES30.glUniform1i(motionTexLoc, 2)

        GLES30.glUniform2f(texelSizeLoc, 1f / safeCapW, 1f / safeCapH)
        if (outputTexelSizeLoc >= 0) {
            GLES30.glUniform2f(outputTexelSizeLoc, 1f / safeOutW, 1f / safeOutH)
        }
        GLES30.glUniform2f(
            motionTexelSizeLoc,
            1f / motionWidth.coerceAtLeast(1),
            1f / motionHeight.coerceAtLeast(1)
        )
        GLES30.glUniform1f(alphaLoc, interpolationAlpha.coerceIn(0f, 1f))
        GLES30.glUniform1f(casLoc, normalizeCasWeight(casSharpness))
        if (fsrUpscaleActiveLoc >= 0) {
            val fsrActive = if (safeCapW < safeOutW || safeCapH < safeOutH) 1 else 0
            GLES30.glUniform1i(fsrUpscaleActiveLoc, fsrActive)
        }
        GLES30.glUniform1i(hudMaskEnabledLoc, if (hudMaskEnabled) 1 else 0)
        GLES30.glUniform4fv(hudRectsLoc, 4, hudProtectedRects, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
        }
    }

    companion object {
        const val MAX_INTERNAL_CAS_WEIGHT = 0.35f

        fun normalizeCasWeight(uiCasSlider: Float): Float {
            return uiCasSlider.coerceIn(0f, 1f) * MAX_INTERNAL_CAS_WEIGHT
        }

        val DEFAULT_FPS_HUD_RECTS = floatArrayOf(
            0.46f, 0.46f, 0.54f, 0.54f,
            0.00f, 0.72f, 0.24f, 1.00f,
            0.00f, 0.00f, 0.30f, 0.38f,
            0.70f, 0.00f, 1.00f, 0.38f
        )
    }
}
