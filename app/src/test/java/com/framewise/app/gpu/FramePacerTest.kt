package com.framewise.app.gpu

import com.framewise.app.device.InterpolationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {

    @Test
    fun competitive60To120_alternatesSynthesizedAndRealFrames() {
        val pacer = FramePacer()

        // No captured frames yet -> skip
        val initial = pacer.evaluateVsyncTick(
            frameTimeNs = 1_000_000_000L,
            mode = InterpolationMode.COMPETITIVE_60_120,
            bypassOpticalFlow = false,
            panelRefreshHz = 120f
        )
        assertEquals(FrameOutputType.SKIP_TICK, initial.outputType)

        // First captured frame -> real passthrough (need 2 frames for motion estimation)
        pacer.onNewCaptureFrameArrived()
        val firstTick = pacer.evaluateVsyncTick(
            frameTimeNs = 1_008_333_333L,
            mode = InterpolationMode.COMPETITIVE_60_120,
            bypassOpticalFlow = false,
            panelRefreshHz = 120f
        )
        assertEquals(FrameOutputType.REAL_PASSTHROUGH, firstTick.outputType)
        assertFalse(firstTick.runMotionEstimationPass)

        // Second captured frame -> alternates [SYNTHESIZED, REAL]
        pacer.onNewCaptureFrameArrived()
        val synthTick = pacer.evaluateVsyncTick(
            frameTimeNs = 1_016_666_666L,
            mode = InterpolationMode.COMPETITIVE_60_120,
            bypassOpticalFlow = false,
            panelRefreshHz = 120f
        )
        assertEquals(FrameOutputType.SYNTHESIZED_INTERMEDIATE, synthTick.outputType)
        assertTrue(synthTick.runMotionEstimationPass)
        assertEquals(0.5f, synthTick.interpolationAlpha, 0.001f)

        val realTick = pacer.evaluateVsyncTick(
            frameTimeNs = 1_025_000_000L,
            mode = InterpolationMode.COMPETITIVE_60_120,
            bypassOpticalFlow = false,
            panelRefreshHz = 120f
        )
        assertEquals(FrameOutputType.REAL_PASSTHROUGH, realTick.outputType)
        assertFalse(realTick.runMotionEstimationPass)
        assertEquals(0.0f, realTick.interpolationAlpha, 0.001f)
    }

    @Test
    fun console30To60_locksOutputTo60HzOn120HzPanel() {
        val pacer = FramePacer()
        pacer.onNewCaptureFrameArrived()
        pacer.onNewCaptureFrameArrived()

        val t0 = 2_000_000_000L
        val slot1 = pacer.evaluateVsyncTick(t0, InterpolationMode.CONSOLE_30_60, false, 120f)
        assertEquals(FrameOutputType.SYNTHESIZED_INTERMEDIATE, slot1.outputType)

        // +8.33ms intermediate 120Hz VSync tick must be skipped to maintain 60Hz pacing
        val skipped = pacer.evaluateVsyncTick(t0 + 8_333_333L, InterpolationMode.CONSOLE_30_60, false, 120f)
        assertEquals(FrameOutputType.SKIP_TICK, skipped.outputType)

        // +16.66ms next 60Hz slot presents real frame
        val slot2 = pacer.evaluateVsyncTick(t0 + 16_666_666L, InterpolationMode.CONSOLE_30_60, false, 120f)
        assertEquals(FrameOutputType.REAL_PASSTHROUGH, slot2.outputType)
        assertEquals(0.0f, slot2.interpolationAlpha, 0.001f)
    }

    @Test
    fun stabilizerAndSmartGateBypass_neverRunOpticalFlow() {
        val pacer = FramePacer()
        pacer.onNewCaptureFrameArrived()
        pacer.onNewCaptureFrameArrived()

        val stabilizer = pacer.evaluateVsyncTick(
            frameTimeNs = 3_000_000_000L,
            mode = InterpolationMode.STABILIZER_60_CAS,
            bypassOpticalFlow = false,
            panelRefreshHz = 60f
        )
        assertEquals(FrameOutputType.REAL_PASSTHROUGH, stabilizer.outputType)
        assertFalse(stabilizer.runMotionEstimationPass)

        val bypassed = pacer.evaluateVsyncTick(
            frameTimeNs = 3_016_666_666L,
            mode = InterpolationMode.COMPETITIVE_60_120,
            bypassOpticalFlow = true,
            panelRefreshHz = 120f
        )
        assertEquals(FrameOutputType.REAL_PASSTHROUGH, bypassed.outputType)
        assertFalse(bypassed.runMotionEstimationPass)
    }
}
