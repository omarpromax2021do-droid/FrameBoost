package com.framewise.app.gpu

import android.opengl.GLES30

/**
 * Downscaled framebuffer target holding bidirectional motion vectors (vx, vy),
 * SAD confidence, and edge magnitude at 25% / 40% / 60% capture scale.
 */
class MotionEstimationFbo {
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var fboId: Int = 0
        private set
    var texId: Int = 0
        private set

    fun ensureAllocated(captureWidth: Int, captureHeight: Int, scalePercent: Int) {
        val clampedScale = scalePercent.coerceIn(20, 80)
        val targetW = ((captureWidth * clampedScale) / 100).coerceAtLeast(32)
        val targetH = ((captureHeight * clampedScale) / 100).coerceAtLeast(32)

        if (fboId != 0 && texId != 0 && width == targetW && height == targetH) {
            return
        }
        release()
        width = targetW
        height = targetH

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        texId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            targetW,
            targetH,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texId,
            0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        if (fboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        }
        if (texId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
        }
        fboId = 0
        texId = 0
        width = 0
        height = 0
    }
}
