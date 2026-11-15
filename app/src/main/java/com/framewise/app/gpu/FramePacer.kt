package com.framewise.app.gpu

import com.framewise.app.device.InterpolationMode

enum class FrameOutputType {
    SYNTHESIZED_INTERMEDIATE,
    REAL_PASSTHROUGH,
    SKIP_TICK
}

data class PacedFrameDecision(
    val outputType: FrameOutputType,
    val runMotionEstimationPass: Boolean,
    val interpolationAlpha: Float,
    val presentationTimeNs: Long
)

class FramePacer {
    private var capturedFrameCount: Long = 0L
    private var hasUnpairedCaptureFrame: Boolean = false
    private var pendingRealFrame: Boolean = false
    private var synthesizedShownForCurrentCapture: Boolean = false
    private var lastPresentedTimeNs: Long = 0L

    fun onNewCaptureFrameArrived() {
        capturedFrameCount++
        pendingRealFrame = true
        if (capturedFrameCount >= 2L) {
            hasUnpairedCaptureFrame = true
            synthesizedShownForCurrentCapture = false
        }
    }

    fun hasPendingWork(): Boolean {
        return hasUnpairedCaptureFrame || pendingRealFrame
    }

    fun evaluateVsyncTick(
        frameTimeNs: Long,
        mode: InterpolationMode,
        bypassOpticalFlow: Boolean,
        panelRefreshHz: Float
    ): PacedFrameDecision {
        if (capturedFrameCount == 0L || (!hasUnpairedCaptureFrame && !pendingRealFrame)) {
            return PacedFrameDecision(
                outputType = FrameOutputType.SKIP_TICK,
                runMotionEstimationPass = false,
                interpolationAlpha = 0f,
                presentationTimeNs = frameTimeNs
            )
        }

        // Lock Console 30->60 cadence to 60Hz (16.66ms) even on 90/120/144Hz panels.
        if (mode == InterpolationMode.CONSOLE_30_60 && panelRefreshHz > 85f && lastPresentedTimeNs > 0L) {
            val deltaNs = frameTimeNs - lastPresentedTimeNs
            if (deltaNs in 1L until CONSOLE_60HZ_MIN_INTERVAL_NS) {
                return PacedFrameDecision(
                    outputType = FrameOutputType.SKIP_TICK,
                    runMotionEstimationPass = false,
                    interpolationAlpha = 0f,
                    presentationTimeNs = frameTimeNs
                )
            }
        }

        if (mode == InterpolationMode.STABILIZER_60_CAS || bypassOpticalFlow || capturedFrameCount < 2L) {
            hasUnpairedCaptureFrame = false
            pendingRealFrame = false
            lastPresentedTimeNs = frameTimeNs
            return PacedFrameDecision(
                outputType = FrameOutputType.REAL_PASSTHROUGH,
                runMotionEstimationPass = false,
                interpolationAlpha = 0f,
                presentationTimeNs = frameTimeNs
            )
        }

        if (hasUnpairedCaptureFrame && !synthesizedShownForCurrentCapture) {
            synthesizedShownForCurrentCapture = true
            lastPresentedTimeNs = frameTimeNs
            return PacedFrameDecision(
                outputType = FrameOutputType.SYNTHESIZED_INTERMEDIATE,
                runMotionEstimationPass = true,
                interpolationAlpha = 0.5f,
                presentationTimeNs = frameTimeNs
            )
        }

        hasUnpairedCaptureFrame = false
        pendingRealFrame = false
        lastPresentedTimeNs = frameTimeNs
        return PacedFrameDecision(
            outputType = FrameOutputType.REAL_PASSTHROUGH,
            runMotionEstimationPass = false,
            interpolationAlpha = 0f,
            presentationTimeNs = frameTimeNs
        )
    }

    fun reset() {
        capturedFrameCount = 0L
        hasUnpairedCaptureFrame = false
        pendingRealFrame = false
        synthesizedShownForCurrentCapture = false
        lastPresentedTimeNs = 0L
    }

    private companion object {
        const val CONSOLE_60HZ_MIN_INTERVAL_NS = 14_500_000L
    }
}
