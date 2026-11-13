package com.framewise.app.gpu

import android.content.Context
import android.opengl.GLES30
import com.framewise.app.R
import java.nio.FloatBuffer

class CasSynthesisShaderProgram(context: Context) {
    private val programId: Int = ShaderUtils.buildQuadProgramFromRaw(context, R.raw.frame_synthesis_cas)
    private val posLoc: Int = GLES30.glGetAttribLocation(programId, "aPosition")
    private val prevTexLoc: Int = GLES30.glGetUniformLocation(programId, "uPrevTex")
    private val currTexLoc: Int = GLES30.glGetUniformLocation(programId, "uCurrTex")
    private val motionTexLoc: Int = GLES30.glGetUniformLocation(programId, "uMotionTex")
    private val texelSizeLoc: Int = GLES30.glGetUniformLocation(programId, "uTexelSize")
    private val motionTexelSizeLoc: Int = GLES30.glGetUniformLocation(programId, "uMotionTexelSize")
    private val alphaLoc: Int = GLES30.glGetUniformLocation(programId, "uInterpolationAlpha")
    private val casLoc: Int = GLES30.glGetUniformLocation(programId, "uCasSharpness")
    private val hudMaskEnabledLoc: Int = GLES30.glGetUniformLocation(programId, "uHudMaskEnabled")
    private val hudRectsLoc: Int = GLES30.glGetUniformLocation(programId, "uHudProtectedRects")

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
        hudProtectedRects: FloatArray = DEFAULT_FPS_HUD_RECTS
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

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

        GLES30.glUniform2f(
            texelSizeLoc,
            1f / captureWidth.coerceAtLeast(1),
            1f / captureHeight.coerceAtLeast(1)
        )
        GLES30.glUniform2f(
            motionTexelSizeLoc,
            1f / motionWidth.coerceAtLeast(1),
            1f / motionHeight.coerceAtLeast(1)
        )
        GLES30.glUniform1f(alphaLoc, interpolationAlpha.coerceIn(0f, 1f))
        GLES30.glUniform1f(casLoc, casSharpness.coerceIn(0f, 1f))
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
        // Normalized UV rects (minX, minY, maxX, maxY): center crosshair, minimap, left & right thumb zones.
        val DEFAULT_FPS_HUD_RECTS = floatArrayOf(
            0.46f, 0.46f, 0.54f, 0.54f,
            0.00f, 0.72f, 0.24f, 1.00f,
            0.00f, 0.00f, 0.30f, 0.38f,
            0.70f, 0.00f, 1.00f, 0.38f
        )
    }
}
