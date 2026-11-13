package com.framewise.app.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsTrackerTest {

    @Test
    fun snapshot_computesRealRollingCaptureAndOutputFps() {
        val tracker = MetricsTracker(rollingWindowNs = 500_000_000L)
        val interval60HzNs = 16_666_667L
        val interval30HzNs = 33_333_333L

        var capTime = 1_000_000_000L
        for (i in 0 until 10) {
            tracker.recordCaptureFrame(capTime)
            capTime += interval30HzNs
        }

        var outTime = 1_008_000_000L
        for (i in 0 until 19) {
            tracker.recordOutputFrame(
                swapCompletionNs = outTime,
                gpuShaderTimeMs = 0.75,
                captureSourceNs = outTime - 8_000_000L
            )
            outTime += interval60HzNs
        }

        val lastOut = outTime - interval60HzNs
        val snap = tracker.snapshot(nowNs = lastOut, activePreset = "30->60 (1x)")

        assertEquals(30.0f, snap.captureFps, 0.5f)
        assertEquals(60.0f, snap.outputFps, 0.5f)
        assertEquals(16.67f, snap.frameTimeMs, 0.1f)
        assertEquals(0.75f, snap.gpuPassTimeMs, 0.05f)
        assertEquals(8.0f, snap.pipelineLatencyMs, 0.05f)
        assertEquals(10L, snap.capturedFramesTotal)
        assertEquals(19L, snap.renderedFramesTotal)
        assertEquals("30->60 (1x)", snap.activePreset)
    }

    @Test
    fun snapshot_prunesStaleFramesOutsideRollingWindow() {
        val tracker = MetricsTracker(rollingWindowNs = 500_000_000L)
        tracker.recordCaptureFrame(1_000_000_000L)
        tracker.recordCaptureFrame(1_016_666_667L)
        tracker.recordOutputFrame(1_020_000_000L, gpuShaderTimeMs = 1.1, captureSourceNs = 1_016_666_667L)
        tracker.recordOutputFrame(1_036_666_667L, gpuShaderTimeMs = 1.1, captureSourceNs = 1_016_666_667L)

        // Advance clock by 2 seconds with no new frames -> rolling FPS must drop to 0.
        val snap = tracker.snapshot(nowNs = 3_050_000_000L, activePreset = "1x Passthrough")
        assertEquals(0f, snap.captureFps, 0.01f)
        assertEquals(0f, snap.outputFps, 0.01f)
        assertEquals(0f, snap.frameTimeMs, 0.01f)
    }
}
