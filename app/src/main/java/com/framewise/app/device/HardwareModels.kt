package com.framewise.app.device

import java.util.Locale

enum class HardwareTier {
    LOW_LEGACY_60HZ,
    MID_RANGE_120HZ,
    HIGH_FLAGSHIP,
    UNSUPPORTED
}

enum class InterpolationMode {
    CONSOLE_30_60,
    COMPETITIVE_60_120,
    STABILIZER_60_CAS
}

data class DisplayModeSpec(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRateHz: Float
)

data class RecommendedPreset(
    val mode: InterpolationMode,
    val motionScalePercent: Int,
    val casSharpness: Float,
    val targetFps: Int
)

data class GpuCalibrationResult(
    val averagePassMs: Double,
    val estimatedMaxFps: Int,
    val glVendor: String,
    val glRenderer: String,
    val glesVersion: String,
    val recommendedTier: HardwareTier,
    val recommendedMode: InterpolationMode,
    val recommendedMotionScalePercent: Int,
    val recommendedCasSharpness: Float,
    val recommendedTargetFps: Int
)

data class DeviceHardwareProfile(
    val deviceModel: String,
    val socModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val glVendor: String,
    val glRenderer: String,
    val glesVersion: String,
    val glesMajorVersion: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val supportedDisplayModes: List<DisplayModeSpec>,
    val maxRefreshRateHz: Float,
    val hardwareTier: HardwareTier,
    val lastCalibrationMs: Double?
) {
    fun allowedTargetFpsOptions(): List<Int> {
        return HardwareTierClassifier.supportedTargetFps(
            maxRefreshHz = maxRefreshRateHz,
            supportedRates = supportedDisplayModes.map { it.refreshRateHz }
        )
    }

    fun preferredModeIdForTargetFps(targetFps: Int): Int? {
        if (supportedDisplayModes.isEmpty()) return null
        val cappedTarget = allowedTargetFpsOptions().lastOrNull { it <= targetFps } ?: 60
        val matchingModes = supportedDisplayModes.filter {
            kotlin.math.abs(it.refreshRateHz - cappedTarget.toFloat()) <= 2.5f
        }
        val bestMatch = matchingModes.maxByOrNull { it.width.toLong() * it.height.toLong() }
        if (bestMatch != null) return bestMatch.modeId
        return supportedDisplayModes.maxByOrNull { it.refreshRateHz }?.modeId
    }

    fun toMarkdownCompatibilityReport(appName: String, versionName: String): String {
        val modesSummary = if (supportedDisplayModes.isEmpty()) {
            "Unknown"
        } else {
            supportedDisplayModes
                .sortedByDescending { it.refreshRateHz }
                .joinToString("\n") { mode ->
                    "  - Mode #${mode.modeId}: ${mode.width}x${mode.height} @ ${
                        String.format(Locale.US, "%.1f", mode.refreshRateHz)
                    } Hz"
                }
        }
        val calibrationLine = lastCalibrationMs?.let {
            String.format(Locale.US, "%.2f ms (est. max %d FPS)", it, HardwareTierClassifier.estimateMaxFps(it))
        } ?: "Not calibrated"

        return buildString {
            appendLine("# $appName Compatibility Report")
            appendLine("- **App Version**: v$versionName")
            appendLine("- **Device Model**: $deviceModel")
            appendLine("- **SoC**: $socModel")
            appendLine("- **Android**: $androidVersion (API $sdkInt)")
            appendLine("- **GPU Vendor**: $glVendor")
            appendLine("- **GPU Renderer**: $glRenderer")
            appendLine("- **OpenGL ES**: $glesVersion")
            appendLine("- **Memory**: $availableRamMb MB free / $totalRamMb MB total")
            appendLine("- **Max Panel Refresh**: ${String.format(Locale.US, "%.1f", maxRefreshRateHz)} Hz")
            appendLine("- **Allowed Output Targets**: ${allowedTargetFpsOptions().joinToString(", ") { "${it} FPS" }}")
            appendLine("- **Hardware Tier**: $hardwareTier")
            appendLine("- **GPU Shader Calibration**: $calibrationLine")
            appendLine("- **Supported Display Modes**:")
            appendLine(modesSummary)
        }
    }
}

object HardwareTierClassifier {
    private const val MIN_SUPPORTED_SDK = 26
    // 3 GB physical devices typically report ~2600-2900 MB after kernel/modem carveout.
    private const val MIN_SUPPORTED_RAM_MB = 2400L
    private const val MIN_SUPPORTED_GLES_MAJOR = 3

    fun parseGlesMajorVersion(versionString: String): Int {
        val match = Regex("""OpenGL ES(?:-CM)?\s+(\d+)\.(\d+)""").find(versionString)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    fun resolveAndroidVersionLabel(sdkInt: Int, releaseOrCodename: String? = null): String {
        return when {
            sdkInt >= 38 -> "Android ${sdkInt - 20} (Future/17+)"
            sdkInt == 37 -> "Android 17"
            sdkInt == 36 -> "Android 16"
            sdkInt == 35 -> "Android 15"
            sdkInt == 34 -> "Android 14"
            sdkInt == 33 -> "Android 13"
            sdkInt == 32 -> "Android 12L"
            sdkInt == 31 -> "Android 12"
            sdkInt == 30 -> "Android 11"
            sdkInt == 29 -> "Android 10"
            sdkInt == 28 -> "Android 9"
            sdkInt == 27 -> "Android 8.1"
            sdkInt == 26 -> "Android 8.0"
            !releaseOrCodename.isNullOrBlank() -> "Android $releaseOrCodename (API $sdkInt)"
            else -> "Android (API $sdkInt)"
        }
    }

    fun estimateMaxFps(avgPassMs: Double): Int {
        if (avgPassMs <= 0.05) return 240
        return (1000.0 / avgPassMs).toInt().coerceIn(1, 240)
    }

    fun supportedTargetFps(maxRefreshHz: Float, supportedRates: List<Float>): List<Int> {
        val effectiveMax = maxOf(maxRefreshHz, supportedRates.maxOrNull() ?: 60f)
        val targets = mutableListOf(60)
        if (effectiveMax >= 88f || supportedRates.any { it in 88f..95f }) {
            targets.add(90)
        }
        if (effectiveMax >= 118f || supportedRates.any { it >= 118f }) {
            targets.add(120)
        }
        return targets
    }

    fun classify(
        sdkInt: Int,
        totalRamMb: Long,
        glesMajor: Int,
        maxRefreshHz: Float,
        avgShaderPassMs: Double?
    ): HardwareTier {
        if (sdkInt < MIN_SUPPORTED_SDK || totalRamMb < MIN_SUPPORTED_RAM_MB || glesMajor < MIN_SUPPORTED_GLES_MAJOR) {
            return HardwareTier.UNSUPPORTED
        }

        if (maxRefreshHz < 85f) {
            return HardwareTier.LOW_LEGACY_60HZ
        }

        if (avgShaderPassMs != null) {
            return when {
                avgShaderPassMs < 1.6 && totalRamMb >= 7000L -> HardwareTier.HIGH_FLAGSHIP
                avgShaderPassMs <= 4.5 -> HardwareTier.MID_RANGE_120HZ
                else -> HardwareTier.LOW_LEGACY_60HZ
            }
        }

        return if (totalRamMb >= 7500L && maxRefreshHz >= 115f) {
            HardwareTier.HIGH_FLAGSHIP
        } else {
            HardwareTier.MID_RANGE_120HZ
        }
    }

    fun recommendedPreset(tier: HardwareTier, maxRefreshHz: Float): RecommendedPreset {
        val allowed = supportedTargetFps(maxRefreshHz, listOf(maxRefreshHz))
        val highestAllowed = allowed.lastOrNull() ?: 60

        return when (tier) {
            HardwareTier.HIGH_FLAGSHIP -> RecommendedPreset(
                mode = if (highestAllowed >= 90) InterpolationMode.COMPETITIVE_60_120 else InterpolationMode.CONSOLE_30_60,
                motionScalePercent = 60,
                casSharpness = 0.65f,
                targetFps = highestAllowed
            )

            HardwareTier.MID_RANGE_120HZ -> RecommendedPreset(
                mode = if (highestAllowed >= 90) InterpolationMode.COMPETITIVE_60_120 else InterpolationMode.CONSOLE_30_60,
                motionScalePercent = 40,
                casSharpness = 0.65f,
                targetFps = highestAllowed
            )

            HardwareTier.LOW_LEGACY_60HZ,
            HardwareTier.UNSUPPORTED -> RecommendedPreset(
                mode = InterpolationMode.STABILIZER_60_CAS,
                motionScalePercent = 25,
                casSharpness = 0.70f,
                targetFps = 60
            )
        }
    }
}
