package com.framewise.app.gpu

data class DisplayMetricsSpec(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val captureWidth: Int,
    val captureHeight: Int,
    val motionWidth: Int,
    val motionHeight: Int,
    val densityDpi: Int
)

object DisplayResizeState {

    /**
     * Computes aligned capture, surface, and downscaled motion-estimation dimensions
     * matching the active Portrait or Landscape orientation.
     */
    fun computeResizeSpec(
        rawWidth: Int,
        rawHeight: Int,
        densityDpi: Int,
        motionScalePercent: Int
    ): DisplayMetricsSpec {
        val surfaceW = rawWidth.coerceAtLeast(1)
        val surfaceH = rawHeight.coerceAtLeast(1)
        val captureW = (rawWidth and 1.inv()).coerceAtLeast(16)
        val captureH = (rawHeight and 1.inv()).coerceAtLeast(16)
        val clampedScale = motionScalePercent.coerceIn(20, 80)
        val motionW = ((captureW * clampedScale) / 100).coerceAtLeast(32)
        val motionH = ((captureH * clampedScale) / 100).coerceAtLeast(32)
        return DisplayMetricsSpec(
            surfaceWidth = surfaceW,
            surfaceHeight = surfaceH,
            captureWidth = captureW,
            captureHeight = captureH,
            motionWidth = motionW,
            motionHeight = motionH,
            densityDpi = densityDpi.coerceAtLeast(120)
        )
    }

    /**
     * Keeps the overlay surface invisible (alpha = 0) during orientation changes,
     * initial warmup frames, or ultra-low-FPS fallback so the underlying game never shows black.
     */
    fun shouldKeepOverlayTransparent(
        isResizing: Boolean,
        validFramesInCurrentOrientation: Int,
        bypassOpticalFlow: Boolean,
        captureFps: Float
    ): Boolean {
        if (isResizing) return true
        if (validFramesInCurrentOrientation < 3) return true
        if (bypassOpticalFlow && captureFps < 20f) return true
        return false
    }
}
