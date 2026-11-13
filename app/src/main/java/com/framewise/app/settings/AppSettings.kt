package com.framewise.app.settings

import com.framewise.app.device.HardwareTier
import com.framewise.app.device.InterpolationMode

data class AppSettings(
    val languageTag: String = "en",
    val onboardingCompleted: Boolean = false,
    val interpolationMode: InterpolationMode = InterpolationMode.CONSOLE_30_60,
    val motionScalePercent: Int = 40,
    val casSharpness: Float = 0.65f,
    val targetFps: Int = 60,
    val preferredDisplayModeId: Int = 0,
    val autoPilotEnabled: Boolean = true,
    val hudMaskEnabled: Boolean = true,
    val thermalGuardEnabled: Boolean = true,
    val smartGateEnabled: Boolean = true,
    val checkUpdatesOnStartup: Boolean = true,
    val lastCalibrationMs: Double? = null,
    val hardwareTier: HardwareTier = HardwareTier.MID_RANGE_120HZ,
    val cachedReleaseEtag: String? = null,
    val cachedReleaseJson: String? = null
)
