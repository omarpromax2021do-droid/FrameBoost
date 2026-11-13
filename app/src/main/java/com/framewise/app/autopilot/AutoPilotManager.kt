package com.framewise.app.autopilot

import com.framewise.app.device.HardwareTier
import com.framewise.app.device.InterpolationMode

data class ResolvedAutoPilotPreset(
    val packageName: String,
    val displayName: String,
    val category: GameProfileCategory,
    val mode: InterpolationMode,
    val motionScalePercent: Int,
    val casSharpness: Float,
    val targetFps: Int,
    val hudMaskEnabled: Boolean,
    val hudZones: HudProtectionZonesConfig,
    val minCaptureFpsGate: Float,
    val presetLabel: String
)

object AutoPilotManager {
    private val IGNORED_EXACT_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher"
    )

    fun isIgnoredPackage(
        packageName: String?,
        ownPackageName: String = "com.framewise.app"
    ): Boolean {
        if (packageName.isNullOrBlank()) return true
        if (packageName == ownPackageName) return true
        if (packageName in IGNORED_EXACT_PACKAGES) return true
        val lower = packageName.lowercase()
        return lower.contains("inputmethod") || lower.contains("keyboard")
    }

    fun resolvePresetForPackage(
        packageName: String,
        hardwareTier: HardwareTier,
        maxRefreshHz: Float,
        customProfiles: Map<String, GameProfile> = emptyMap(),
        userHudZones: HudProtectionZonesConfig = HudProtectionZonesConfig()
    ): ResolvedAutoPilotPreset? {
        val profile = GameProfileRepository.findProfile(packageName, customProfiles) ?: return null
        val supportsHighRefresh = maxRefreshHz >= 88f
        val highRefreshRateTarget = if (maxRefreshHz >= 118f) 120 else 90

        return when (profile.category) {
            GameProfileCategory.COD_MOBILE -> {
                if (supportsHighRefresh && hardwareTier != HardwareTier.LOW_LEGACY_60HZ) {
                    ResolvedAutoPilotPreset(
                        packageName = profile.packageName,
                        displayName = profile.displayName,
                        category = profile.category,
                        mode = InterpolationMode.COMPETITIVE_60_120,
                        motionScalePercent = 40,
                        casSharpness = 0.65f,
                        targetFps = highRefreshRateTarget,
                        hudMaskEnabled = true,
                        hudZones = userHudZones,
                        minCaptureFpsGate = 40f,
                        presetLabel = "CODM 60->$highRefreshRateTarget"
                    )
                } else if (hardwareTier == HardwareTier.LOW_LEGACY_60HZ || hardwareTier == HardwareTier.UNSUPPORTED) {
                    ResolvedAutoPilotPreset(
                        packageName = profile.packageName,
                        displayName = profile.displayName,
                        category = profile.category,
                        mode = InterpolationMode.STABILIZER_60_CAS,
                        motionScalePercent = 25,
                        casSharpness = 0.70f,
                        targetFps = 60,
                        hudMaskEnabled = true,
                        hudZones = userHudZones,
                        minCaptureFpsGate = 40f,
                        presetLabel = "CODM 60Hz CAS"
                    )
                } else {
                    ResolvedAutoPilotPreset(
                        packageName = profile.packageName,
                        displayName = profile.displayName,
                        category = profile.category,
                        mode = InterpolationMode.CONSOLE_30_60,
                        motionScalePercent = 25,
                        casSharpness = 0.70f,
                        targetFps = 60,
                        hudMaskEnabled = true,
                        hudZones = userHudZones,
                        minCaptureFpsGate = 24f,
                        presetLabel = "CODM 30->60 Console"
                    )
                }
            }

            GameProfileCategory.RDR1_HEAVY_AAA -> {
                ResolvedAutoPilotPreset(
                    packageName = profile.packageName,
                    displayName = profile.displayName,
                    category = profile.category,
                    mode = InterpolationMode.CONSOLE_30_60,
                    motionScalePercent = 25,
                    casSharpness = 0.85f,
                    targetFps = 60,
                    hudMaskEnabled = true,
                    hudZones = userHudZones,
                    minCaptureFpsGate = 24f,
                    presetLabel = "AAA 30->60 CAS"
                )
            }

            GameProfileCategory.CUSTOM -> {
                val safeMode = if (profile.preferredMode == InterpolationMode.COMPETITIVE_60_120 && !supportsHighRefresh) {
                    InterpolationMode.CONSOLE_30_60
                } else {
                    profile.preferredMode
                }
                val targetFps = if (safeMode == InterpolationMode.COMPETITIVE_60_120 && supportsHighRefresh) {
                    highRefreshRateTarget
                } else {
                    60
                }
                val safeScale = when (profile.preferredMotionScale) {
                    25, 40, 60 -> profile.preferredMotionScale
                    else -> 40
                }
                ResolvedAutoPilotPreset(
                    packageName = profile.packageName,
                    displayName = profile.displayName,
                    category = profile.category,
                    mode = safeMode,
                    motionScalePercent = safeScale,
                    casSharpness = profile.preferredCasSharpness.coerceIn(0f, 1f),
                    targetFps = targetFps,
                    hudMaskEnabled = profile.hudMaskEnabled,
                    hudZones = userHudZones,
                    minCaptureFpsGate = if (safeMode == InterpolationMode.CONSOLE_30_60) 24f else 40f,
                    presetLabel = "${profile.displayName.take(12)} ${if (targetFps >= 90) targetFps else 60}Hz"
                )
            }
        }
    }
}
