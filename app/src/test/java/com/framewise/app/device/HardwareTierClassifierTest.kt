package com.framewise.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareTierClassifierTest {

    @Test
    fun parseGlesMajorVersion_extractsMajorVersion() {
        assertEquals(3, HardwareTierClassifier.parseGlesMajorVersion("OpenGL ES 3.2 V@0530.0"))
        assertEquals(3, HardwareTierClassifier.parseGlesMajorVersion("OpenGL ES 3.0"))
        assertEquals(2, HardwareTierClassifier.parseGlesMajorVersion("OpenGL ES 2.0"))
        assertEquals(0, HardwareTierClassifier.parseGlesMajorVersion("Unknown"))
    }

    @Test
    fun classify_enforcesHardMinimums() {
        assertEquals(
            HardwareTier.UNSUPPORTED,
            HardwareTierClassifier.classify(
                sdkInt = 25,
                totalRamMb = 4096,
                glesMajor = 3,
                maxRefreshHz = 60f,
                avgShaderPassMs = 1.0
            )
        )
        assertEquals(
            HardwareTier.UNSUPPORTED,
            HardwareTierClassifier.classify(
                sdkInt = 30,
                totalRamMb = 1800,
                glesMajor = 3,
                maxRefreshHz = 60f,
                avgShaderPassMs = 1.0
            )
        )
        assertEquals(
            HardwareTier.UNSUPPORTED,
            HardwareTierClassifier.classify(
                sdkInt = 30,
                totalRamMb = 4096,
                glesMajor = 2,
                maxRefreshHz = 60f,
                avgShaderPassMs = 1.0
            )
        )
    }

    @Test
    fun classify_identifiesLegacyMidRangeAndFlagshipTiers() {
        assertEquals(
            HardwareTier.LOW_LEGACY_60HZ,
            HardwareTierClassifier.classify(
                sdkInt = 29,
                totalRamMb = 4096,
                glesMajor = 3,
                maxRefreshHz = 60f,
                avgShaderPassMs = 2.0
            )
        )
        // Moto G85 / Adreno 619 class (120Hz, 8GB RAM, ~2.2ms pass)
        assertEquals(
            HardwareTier.MID_RANGE_120HZ,
            HardwareTierClassifier.classify(
                sdkInt = 34,
                totalRamMb = 7600,
                glesMajor = 3,
                maxRefreshHz = 120f,
                avgShaderPassMs = 2.2
            )
        )
        // Flagship class (120Hz, 12GB RAM, <1.6ms pass)
        assertEquals(
            HardwareTier.HIGH_FLAGSHIP,
            HardwareTierClassifier.classify(
                sdkInt = 34,
                totalRamMb = 11500,
                glesMajor = 3,
                maxRefreshHz = 120f,
                avgShaderPassMs = 0.9
            )
        )
    }

    @Test
    fun supportedTargetFps_strictlyCapsToPanelCapabilities() {
        assertEquals(
            listOf(60),
            HardwareTierClassifier.supportedTargetFps(60f, listOf(60f))
        )
        assertEquals(
            listOf(60, 90),
            HardwareTierClassifier.supportedTargetFps(90f, listOf(60f, 90f))
        )
        assertEquals(
            listOf(60, 90, 120),
            HardwareTierClassifier.supportedTargetFps(120f, listOf(60f, 90f, 120f))
        )
    }
}
