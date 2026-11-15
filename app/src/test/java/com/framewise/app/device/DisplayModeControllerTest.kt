package com.framewise.app.device

import com.framewise.app.capture.DisplayRefreshController
import com.framewise.app.gpu.AdpfSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModeControllerTest {

    @Test
    fun supportedTargetFps_andPreferredModeId_unlockAllPanelRefreshRates() {
        val modes = listOf(
            DisplayModeSpec(modeId = 1, width = 1080, height = 2400, refreshRateHz = 60f),
            DisplayModeSpec(modeId = 2, width = 1080, height = 2400, refreshRateHz = 90f),
            DisplayModeSpec(modeId = 3, width = 1080, height = 2400, refreshRateHz = 120f),
            DisplayModeSpec(modeId = 4, width = 1080, height = 2400, refreshRateHz = 144f),
            DisplayModeSpec(modeId = 5, width = 1080, height = 2400, refreshRateHz = 165f)
        )
        val profile = DeviceHardwareProfile(
            deviceModel = "Test Flagship",
            socModel = "SM8650",
            androidVersion = "Android 15",
            sdkInt = 35,
            glVendor = "Qualcomm",
            glRenderer = "Adreno 750",
            glesVersion = "OpenGL ES 3.2",
            glesMajorVersion = 3,
            totalRamMb = 12288L,
            availableRamMb = 7200L,
            supportedDisplayModes = modes,
            maxRefreshRateHz = 165f,
            hardwareTier = HardwareTier.HIGH_FLAGSHIP,
            lastCalibrationMs = 1.1
        )

        assertEquals(listOf(60, 90, 120, 144, 165), profile.allowedTargetFpsOptions())
        assertEquals(1, profile.preferredModeIdForTargetFps(60))
        assertEquals(2, profile.preferredModeIdForTargetFps(90))
        assertEquals(3, profile.preferredModeIdForTargetFps(120))
        assertEquals(4, profile.preferredModeIdForTargetFps(144))
        assertEquals(5, profile.preferredModeIdForTargetFps(165))
    }

    @Test
    fun refreshKeysAndAdpfDuration_coverStandardOemKeysAndHighHz() {
        val keys = DisplayRefreshController.SYSTEM_REFRESH_KEYS.toSet()
        assertTrue(keys.contains("min_refresh_rate"))
        assertTrue(keys.contains("peak_refresh_rate"))
        assertTrue(keys.contains("user_refresh_rate"))
        assertTrue(keys.contains("ScreenRefreshRate"))

        assertEquals(16_666_666L, AdpfSessionManager.computeTargetDurationNanos(60f))
        assertEquals(8_333_333L, AdpfSessionManager.computeTargetDurationNanos(120f))
        assertEquals(6_944_444L, AdpfSessionManager.computeTargetDurationNanos(144f))
    }
}
