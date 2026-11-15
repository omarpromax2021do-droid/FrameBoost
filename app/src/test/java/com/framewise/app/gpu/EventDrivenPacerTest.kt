package com.framewise.app.gpu

import com.framewise.app.device.InterpolationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDrivenPacerTest {

    @Test
    fun framePacer_drivesZeroIdleTicksAfterCapturePairCompletes() {
        val pacer = FramePacer()
        assertFalse(pacer.hasPendingWork())

        pacer.onNewCaptureFrameArrived()
        assertTrue(pacer.hasPendingWork())
        val first = pacer.evaluateVsyncTick(1_000_000_000L, InterpolationMode.COMPETITIVE_60_120, false, 120f)
        assertEquals(FrameOutputType.REAL_PASSTHROUGH, first.outputType)
        assertFalse(pacer.hasPendingWork())

        pacer.onNewCaptureFrameArrived()
        assertTrue(pacer.hasPendingWork())
        val synth = pacer.evaluateVsyncTick(1_016_666_666L, InterpolationMode.COMPETITIVE_60_120, false, 120f)
        assertEquals(FrameOutputType.SYNTHESIZED_INTERMEDIATE, synth.outputType)
        assertTrue(synth.runMotionEstimationPass)

        val real = pacer.evaluateVsyncTick(1_025_000_000L, InterpolationMode.COMPETITIVE_60_120, false, 120f)
        assertEquals(FrameOutputType.REAL_PASSTHROUGH, real.outputType)
        assertFalse(pacer.hasPendingWork())

        val idle = pacer.evaluateVsyncTick(1_033_333_333L, InterpolationMode.COMPETITIVE_60_120, false, 120f)
        assertEquals(FrameOutputType.SKIP_TICK, idle.outputType)
    }

    @Test
    fun fsrSpatialScaling_casNormalization_andImmediateSmartGateRecovery() {
        val spec40 = DisplayResizeState.computeResizeSpec(2400, 1080, 400, 40)
        assertEquals(1800, spec40.captureWidth)
        assertEquals(810, spec40.captureHeight)
        assertEquals(720, spec40.motionWidth)
        assertEquals(324, spec40.motionHeight)

        val spec60 = DisplayResizeState.computeResizeSpec(2400, 1080, 400, 60)
        assertEquals(2400, spec60.captureWidth)
        assertEquals(1080, spec60.captureHeight)

        assertEquals(0.0f, CasSynthesisShaderProgram.normalizeCasWeight(0f), 0.0001f)
        assertEquals(0.35f, CasSynthesisShaderProgram.normalizeCasWeight(1.0f), 0.0001f)
        assertEquals(0.35f, CasSynthesisShaderProgram.normalizeCasWeight(2.0f), 0.0001f)

        val gate = SmartGateController(downgradeHoldNs = 1_500_000_000L, restoreHoldNs = 0L)
        gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 26f, 2.0f, 1_000_000_000L)
        val downgraded = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 26f, 2.0f, 2_600_000_000L)
        assertTrue(downgraded.bypassOpticalFlow)

        val recovered = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 42f, 2.0f, 2_616_666_666L)
        assertFalse(recovered.bypassOpticalFlow)
    }
}
