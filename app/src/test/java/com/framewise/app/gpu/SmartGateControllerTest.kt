package com.framewise.app.gpu

import com.framewise.app.device.InterpolationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartGateControllerTest {

    @Test
    fun lowFpsFallback_respectsHysteresisAndRestoresAfterStability() {
        val gate = SmartGateController(
            downgradeHoldNs = 1_500_000_000L,
            restoreHoldNs = 3_000_000_000L
        )

        // Dip starts at t=1.0s
        val d1 = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 34f, 1.8f, 1_000_000_000L)
        assertFalse(d1.bypassOpticalFlow)

        // At t=2.4s (<1.5s) still does not oscillate
        val d2 = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 34f, 1.8f, 2_400_000_000L)
        assertFalse(d2.bypassOpticalFlow)

        // At t=2.6s (>=1.5s) switches to CAS-only fallback
        val d3 = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 34f, 1.8f, 2_600_000_000L)
        assertTrue(d3.bypassOpticalFlow)
        assertTrue(d3.statusReason.contains("Low Base FPS (<40)"))

        // Recovery starts at t=4.0s, still bypassed before 3.0s stability
        val d4 = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 58f, 1.5f, 4_000_000_000L)
        assertTrue(d4.bypassOpticalFlow)

        // At t=7.1s (>=3.0s stable) restores interpolation
        val d5 = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 58f, 1.5f, 7_100_000_000L)
        assertFalse(d5.bypassOpticalFlow)
    }

    @Test
    fun consoleMode_enforces24FpsThreshold() {
        val gate = SmartGateController(downgradeHoldNs = 1_500_000_000L)

        // 26 FPS stays active in 30->60 Console mode
        gate.evaluate(true, InterpolationMode.CONSOLE_30_60, 25, 26f, 2.0f, 1_000_000_000L)
        val okDecision = gate.evaluate(true, InterpolationMode.CONSOLE_30_60, 25, 26f, 2.0f, 3_000_000_000L)
        assertFalse(okDecision.bypassOpticalFlow)

        // 21 FPS (<24) for 1.6s triggers Console fallback
        gate.evaluate(true, InterpolationMode.CONSOLE_30_60, 25, 21f, 2.0f, 4_000_000_000L)
        val fallback = gate.evaluate(true, InterpolationMode.CONSOLE_30_60, 25, 21f, 2.0f, 5_600_000_000L)
        assertTrue(fallback.bypassOpticalFlow)
        assertTrue(fallback.statusReason.contains("<24"))
    }

    @Test
    fun gpuBudgetOverload_stepsDownScaleProgressively() {
        val gate = SmartGateController(downgradeHoldNs = 1_500_000_000L)

        // Start at Quality 60% with GPU exceeding 5.5ms budget for 120Hz
        gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 60, 60f, 6.4f, 1_000_000_000L)
        val stepTo40 = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 60, 60f, 6.4f, 2_600_000_000L)
        assertFalse(stepTo40.bypassOpticalFlow)
        assertEquals(40, stepTo40.effectiveMotionScalePercent)

        val stepTo25 = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 60, 60f, 6.4f, 4_200_000_000L)
        assertFalse(stepTo25.bypassOpticalFlow)
        assertEquals(25, stepTo25.effectiveMotionScalePercent)

        val stepToCasOnly = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 60, 60f, 6.4f, 5_800_000_000L)
        assertTrue(stepToCasOnly.bypassOpticalFlow)
        assertTrue(stepToCasOnly.statusReason.contains("GPU Overload"))
    }
}
