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

    @Test
    fun defaultSmartGate_immediatelyRecoversFromLowFpsAndStepDownAt60Fps() {
        val gate = SmartGateController(downgradeHoldNs = 1_500_000_000L)

        // Loading screen dip drops FPS below 40, triggering Low Base FPS fallback
        gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 28f, 1.8f, 1_000_000_000L)
        val lowFpsDecision = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 28f, 1.8f, 2_600_000_000L)
        assertTrue(lowFpsDecision.bypassOpticalFlow)
        assertTrue(lowFpsDecision.statusReason.contains("Low Base FPS (<40)"))

        // Brief shader spike steps 40% down to 25%
        gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 60.1f, 6.67f, 3_000_000_000L)
        val steppedDown = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 60.1f, 6.67f, 4_600_000_000L)
        assertEquals(25, steppedDown.effectiveMotionScalePercent)
        assertFalse(steppedDown.statusReason.contains("Low Base FPS"))

        // Once captureFps is 60.1 FPS and pure shader pass is <= 5.5ms, immediately restore 40% Active
        val recovered = gate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 60.1f, 2.1f, 4_650_000_000L)
        assertFalse(recovered.bypassOpticalFlow)
        assertEquals(40, recovered.effectiveMotionScalePercent)
        assertEquals("Active: 60\u2192120 Optical Flow + CAS", recovered.statusReason)
    }

    @Test
    fun landscapeResizeSpec_andTransparentSafeguard_behaveCorrectly() {
        val portrait = DisplayResizeState.computeResizeSpec(
            rawWidth = 1080,
            rawHeight = 2400,
            densityDpi = 400,
            motionScalePercent = 40
        )
        assertEquals(1080, portrait.surfaceWidth)
        assertEquals(2400, portrait.surfaceHeight)

        val landscapeBalanced = DisplayResizeState.computeResizeSpec(
            rawWidth = 2400,
            rawHeight = 1080,
            densityDpi = 400,
            motionScalePercent = 40
        )
        assertEquals(2400, landscapeBalanced.surfaceWidth)
        assertEquals(1080, landscapeBalanced.surfaceHeight)
        assertEquals(1800, landscapeBalanced.captureWidth)
        assertEquals(810, landscapeBalanced.captureHeight)
        assertEquals(720, landscapeBalanced.motionWidth)
        assertEquals(324, landscapeBalanced.motionHeight)

        val landscapeUltraLight = DisplayResizeState.computeResizeSpec(
            rawWidth = 2400,
            rawHeight = 1080,
            densityDpi = 400,
            motionScalePercent = 25
        )
        assertEquals(1800, landscapeUltraLight.captureWidth)
        assertEquals(810, landscapeUltraLight.captureHeight)
        assertEquals(450, landscapeUltraLight.motionWidth)
        assertEquals(202, landscapeUltraLight.motionHeight)

        val landscapeQuality = DisplayResizeState.computeResizeSpec(
            rawWidth = 2400,
            rawHeight = 1080,
            densityDpi = 400,
            motionScalePercent = 60
        )
        assertEquals(2400, landscapeQuality.captureWidth)
        assertEquals(1080, landscapeQuality.captureHeight)
        assertEquals(1440, landscapeQuality.motionWidth)
        assertEquals(648, landscapeQuality.motionHeight)

        assertTrue(DisplayResizeState.shouldKeepOverlayTransparent(isResizing = true, validFramesInCurrentOrientation = 10, bypassOpticalFlow = false, captureFps = 60f))
        assertTrue(DisplayResizeState.shouldKeepOverlayTransparent(isResizing = false, validFramesInCurrentOrientation = 2, bypassOpticalFlow = false, captureFps = 60f))
        assertTrue(DisplayResizeState.shouldKeepOverlayTransparent(isResizing = false, validFramesInCurrentOrientation = 5, bypassOpticalFlow = true, captureFps = 18f))
        assertFalse(DisplayResizeState.shouldKeepOverlayTransparent(isResizing = false, validFramesInCurrentOrientation = 3, bypassOpticalFlow = false, captureFps = 60.1f))
        assertFalse(DisplayResizeState.shouldKeepOverlayTransparent(isResizing = false, validFramesInCurrentOrientation = 5, bypassOpticalFlow = true, captureFps = 35f))
    }

    @Test
    fun casNormalization_hudBoundsClamping_andImmediateLowFpsRecovery() {
        assertEquals(0.0f, CasSynthesisShaderProgram.normalizeCasWeight(0.0f), 0.0001f)
        assertEquals(0.175f, CasSynthesisShaderProgram.normalizeCasWeight(0.5f), 0.0001f)
        assertEquals(0.35f, CasSynthesisShaderProgram.normalizeCasWeight(1.0f), 0.0001f)
        assertEquals(0.35f, CasSynthesisShaderProgram.normalizeCasWeight(1.5f), 0.0001f)

        val clamped = DisplayResizeState.clampHudPositionToSafeBounds(
            x = 2300,
            y = 1050,
            hudWidth = 240,
            hudHeight = 120,
            screenWidth = 1080,
            screenHeight = 2400,
            marginPx = 16,
            minTopPx = 48
        )
        assertEquals(824, clamped.first)
        assertEquals(1050, clamped.second)

        val landscapeClamped = DisplayResizeState.clampHudPositionToSafeBounds(
            x = -50,
            y = 2200,
            hudWidth = 240,
            hudHeight = 120,
            screenWidth = 2400,
            screenHeight = 1080,
            marginPx = 16,
            minTopPx = 48
        )
        assertEquals(16, landscapeClamped.first)
        assertEquals(944, landscapeClamped.second)

        val immediateGate = SmartGateController(downgradeHoldNs = 1_500_000_000L, restoreHoldNs = 0L)
        immediateGate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 28f, 1.8f, 1_000_000_000L)
        val tripped = immediateGate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 28f, 1.8f, 2_600_000_000L)
        assertTrue(tripped.bypassOpticalFlow)

        val restored = immediateGate.evaluate(true, InterpolationMode.COMPETITIVE_60_120, 40, 45f, 1.8f, 2_616_000_000L)
        assertFalse(restored.bypassOpticalFlow)
        assertEquals("Active: 60\u2192120 Optical Flow + CAS", restored.statusReason)
    }
}
