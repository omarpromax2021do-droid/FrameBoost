package com.framewise.app.gpu

import android.content.Context
import android.opengl.GLES30
import com.framewise.app.R
import java.nio.FloatBuffer

class OpticalFlowShaderProgram(context: Context) {
    private val programId: Int = ShaderUtils.buildQuadProgramFromRaw(context, R.raw.motion_estimation)
    private val posLoc: Int = GLES30.glGetAttribLocation(programId, "aPosition")
    private val prevTexLoc: Int = GLES30.glGetUniformLocation(programId, "uPrevTex")
    private val currTexLoc: Int = GLES30.glGetUniformLocation(programId, "uCurrTex")
    private val texelSizeLoc: Int = GLES30.glGetUniformLocation(programId, "uTexelSize")

    fun execute(
        quadBuffer: FloatBuffer,
        prevTexId: Int,
        currTexId: Int,
        captureWidth: Int,
        captureHeight: Int,
        targetMotionFbo: MotionEstimationFbo
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetMotionFbo.fboId)
        GLES30.glViewport(0, 0, targetMotionFbo.width, targetMotionFbo.height)
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

        GLES30.glUniform2f(
            texelSizeLoc,
            1f / captureWidth.coerceAtLeast(1),
            1f / captureHeight.coerceAtLeast(1)
        )

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
        }
    }
}
