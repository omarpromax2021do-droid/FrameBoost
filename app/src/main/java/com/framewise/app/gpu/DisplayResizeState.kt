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

    fun spatialCaptureScalePercent(motionScalePercent: Int): Int {
        return if (motionScalePercent <= 40) 75 else 100
    }

    fun computeResizeSpec(
        rawWidth: Int,
        rawHeight: Int,
        densityDpi: Int,
        motionScalePercent: Int
    ): DisplayMetricsSpec {
        val surfaceW = rawWidth.coerceAtLeast(1)
        val surfaceH = rawHeight.coerceAtLeast(1)
        val spatialScale = spatialCaptureScalePercent(motionScalePercent)
        val captureW = (((surfaceW * spatialScale) / 100) and 1.inv()).coerceAtLeast(16)
        val captureH = (((surfaceH * spatialScale) / 100) and 1.inv()).coerceAtLeast(16)
        val clampedScale = motionScalePercent.coerceIn(20, 80)
        val motionW = (((captureW * clampedScale) / 100) and 1.inv()).coerceAtLeast(32)
        val motionH = (((captureH * clampedScale) / 100) and 1.inv()).coerceAtLeast(32)
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

    fun clampHudPositionToSafeBounds(
        x: Int,
        y: Int,
        hudWidth: Int,
        hudHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int,
        minTopPx: Int
    ): Pair<Int, Int> {
        val safeW = hudWidth.coerceAtLeast(1)
        val safeH = hudHeight.coerceAtLeast(1)
        val maxX = (screenWidth - safeW - marginPx).coerceAtLeast(marginPx)
        val maxY = (screenHeight - safeH - marginPx).coerceAtLeast(minTopPx)
        val clampedX = x.coerceIn(marginPx, maxX)
        val clampedY = y.coerceIn(minTopPx, maxY)
        return clampedX to clampedY
    }

    // Keep overlay transparent during orientation transitions and initial capture warmup to prevent black flashes.
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
