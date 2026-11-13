package com.framewise.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfilerTest {

    @Test
    fun resolveAndroidVersionLabel_supportsAndroid14Through18Plus() {
        assertEquals("Android 14", HardwareTierClassifier.resolveAndroidVersionLabel(34))
        assertEquals("Android 15", HardwareTierClassifier.resolveAndroidVersionLabel(35))
        assertEquals("Android 16", HardwareTierClassifier.resolveAndroidVersionLabel(36))
        assertEquals("Android 17", HardwareTierClassifier.resolveAndroidVersionLabel(37))
        assertEquals("Android 18 (Future/17+)", HardwareTierClassifier.resolveAndroidVersionLabel(38))
        assertEquals("Android 19 (Future/17+)", HardwareTierClassifier.resolveAndroidVersionLabel(39))
    }

    @Test
    fun classify_treatsAndroid15Through18PlusAsFirstClassSupported() {
        for (sdk in listOf(35, 36, 37, 38)) {
            assertEquals(
                HardwareTier.MID_RANGE_120HZ,
                HardwareTierClassifier.classify(
                    sdkInt = sdk,
                    totalRamMb = 7600L,
                    glesMajor = 3,
                    maxRefreshHz = 120f,
                    avgShaderPassMs = 2.0
                )
            )
            assertEquals(
                HardwareTier.HIGH_FLAGSHIP,
                HardwareTierClassifier.classify(
                    sdkInt = sdk,
                    totalRamMb = 11500L,
                    glesMajor = 3,
                    maxRefreshHz = 120f,
                    avgShaderPassMs = 0.8
                )
            )
        }
    }
}
