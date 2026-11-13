package com.framewise.app.gpu

import android.opengl.GLES30

/**
 * Manages two GPU-resident RGBA8 framebuffer attachments for zero-copy ping-pong
 * frame storage (PrevFrameFbo and CurrFrameFbo).
 */
class PingPongFboPair {
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    var prevFboId: Int = 0
        private set
    var prevTexId: Int = 0
        private set

    var currFboId: Int = 0
        private set
    var currTexId: Int = 0
        private set

    fun ensureAllocated(targetWidth: Int, targetHeight: Int) {
        val safeW = targetWidth.coerceAtLeast(16)
        val safeH = targetHeight.coerceAtLeast(16)
        if (prevFboId != 0 && currFboId != 0 && width == safeW && height == safeH) {
            return
        }
        release()
        width = safeW
        height = safeH

        val prev = createRenderTarget(safeW, safeH)
        val curr = createRenderTarget(safeW, safeH)
        prevFboId = prev.first
        prevTexId = prev.second
        currFboId = curr.first
        currTexId = curr.second
    }

    fun swap() {
        val tmpFbo = prevFboId
        val tmpTex = prevTexId
        prevFboId = currFboId
        prevTexId = currTexId
        currFboId = tmpFbo
        currTexId = tmpTex
    }

    fun release() {
        val fbos = intArrayOf(prevFboId, currFboId).filter { it != 0 }.toIntArray()
        if (fbos.isNotEmpty()) {
            GLES30.glDeleteFramebuffers(fbos.size, fbos, 0)
        }
        val texs = intArrayOf(prevTexId, currTexId).filter { it != 0 }.toIntArray()
        if (texs.isNotEmpty()) {
            GLES30.glDeleteTextures(texs.size, texs, 0)
        }
        prevFboId = 0
        prevTexId = 0
        currFboId = 0
        currTexId = 0
        width = 0
        height = 0
    }

    private fun createRenderTarget(w: Int, h: Int): Pair<Int, Int> {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            w,
            h,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        val fboId = fboIds[0]
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
        return fboId to texId
    }
}
