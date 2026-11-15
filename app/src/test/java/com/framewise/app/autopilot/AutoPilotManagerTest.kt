package com.framewise.app.autopilot

import com.framewise.app.device.HardwareTier
import com.framewise.app.device.InterpolationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPilotManagerTest {

    @Test
    fun isIgnoredPackage_filtersSystemAndInputMethodPackages() {
        assertTrue(AutoPilotManager.isIgnoredPackage(null))
        assertTrue(AutoPilotManager.isIgnoredPackage(""))
        assertTrue(AutoPilotManager.isIgnoredPackage("com.framewise.app"))
        assertTrue(AutoPilotManager.isIgnoredPackage("com.android.systemui"))
        assertTrue(AutoPilotManager.isIgnoredPackage("com.google.android.inputmethod.latin"))
        assertFalse(AutoPilotManager.isIgnoredPackage("com.activision.callofduty.shooter"))
        assertFalse(AutoPilotManager.isIgnoredPackage("com.netflix.NGP.Kamo"))
    }

    @Test
    fun resolvePresetForCodMobile_selects120HzCompetitiveOn120HzAndCapsOn60HzLegacy() {
        val codmPackages = listOf(
            "com.activision.callofduty.shooter",
            "com.garena.game.codm",
            "com.tencent.tmgp.kr.codm",
            "com.vng.codm"
        )
        for (pkg in codmPackages) {
            val highRefreshPreset = AutoPilotManager.resolvePresetForPackage(
                packageName = pkg,
                hardwareTier = HardwareTier.MID_RANGE_120HZ,
                maxRefreshHz = 120f
            )
            assertNotNull(highRefreshPreset)
            assertEquals(InterpolationMode.COMPETITIVE_60_120, highRefreshPreset!!.mode)
            assertEquals(40, highRefreshPreset.motionScalePercent)
            assertEquals(0.65f, highRefreshPreset.casSharpness, 0.001f)
            assertEquals(120, highRefreshPreset.targetFps)
            assertTrue(highRefreshPreset.hudMaskEnabled)

            val legacyPreset = AutoPilotManager.resolvePresetForPackage(
                packageName = pkg,
                hardwareTier = HardwareTier.LOW_LEGACY_60HZ,
                maxRefreshHz = 60f
            )
            assertNotNull(legacyPreset)
            assertEquals(InterpolationMode.STABILIZER_60_CAS, legacyPreset!!.mode)
            assertEquals(25, legacyPreset.motionScalePercent)
            assertEquals(0.70f, legacyPreset.casSharpness, 0.001f)
            assertEquals(60, legacyPreset.targetFps)
        }
    }

    @Test
    fun resolvePresetForRdr1AndAaa_enforcesUltraLightConsoleModeWithHighCas() {
        val aaaPackages = listOf(
            "com.netflix.NGP.Kamo",
            "com.rockstargames.rdr",
            "com.miHoYo.GenshinImpact"
        )
        for (pkg in aaaPackages) {
            val preset = AutoPilotManager.resolvePresetForPackage(
                packageName = pkg,
                hardwareTier = HardwareTier.HIGH_FLAGSHIP,
                maxRefreshHz = 120f
            )
            assertNotNull(preset)
            assertEquals(InterpolationMode.CONSOLE_30_60, preset!!.mode)
            assertEquals(25, preset.motionScalePercent)
            assertEquals(0.85f, preset.casSharpness, 0.001f)
            assertEquals(60, preset.targetFps)
            assertEquals(24f, preset.minCaptureFpsGate, 0.01f)
        }
    }

    @Test
    fun resolvePresetForCustomAndUnknownPackage_handlesCapsCleanly() {
        assertNull(
            AutoPilotManager.resolvePresetForPackage(
                packageName = "com.example.unknown",
                hardwareTier = HardwareTier.MID_RANGE_120HZ,
                maxRefreshHz = 120f
            )
        )

        val custom = GameProfile(
            packageName = "com.example.shooter",
            displayName = "Custom Shooter",
            category = GameProfileCategory.CUSTOM,
            preferredMode = InterpolationMode.COMPETITIVE_60_120,
            preferredMotionScale = 60,
            preferredCasSharpness = 0.72f,
            hudMaskEnabled = true
        )
        val cappedPreset = AutoPilotManager.resolvePresetForPackage(
            packageName = custom.packageName,
            hardwareTier = HardwareTier.LOW_LEGACY_60HZ,
            maxRefreshHz = 60f,
            customProfiles = mapOf(custom.packageName to custom)
        )
        assertNotNull(cappedPreset)
        assertEquals(InterpolationMode.CONSOLE_30_60, cappedPreset!!.mode)
        assertEquals(60, cappedPreset.targetFps)
        assertEquals(0.72f, cappedPreset.casSharpness, 0.001f)
    }

    @Test
    fun universalGameClassifier_andSessionCooldown_preventRepeatTriggers() {
        assertEquals(
            GameCadenceClass.CONSOLE_30_FPS,
            UniversalGameClassifier.classifyCadence("com.miHoYo.GenshinImpact", observedCaptureFps = 0f)
        )
        assertEquals(
            GameCadenceClass.COMPETITIVE_HIGH_FPS,
            UniversalGameClassifier.classifyCadence("com.activision.callofduty.shooter", observedCaptureFps = 58f)
        )

        val t0 = 10_000_000L
        val cooldowns = mapOf("com.activision.callofduty.shooter" to t0)

        assertFalse(
            AutoPilotManager.shouldTriggerGameSession(
                packageName = "com.activision.callofduty.shooter",
                nowMs = t0 + 1_000L,
                lastActiveGamePackage = "com.activision.callofduty.shooter",
                cooldownTimestamps = cooldowns
            )
        )
        assertFalse(
            AutoPilotManager.shouldTriggerGameSession(
                packageName = "com.activision.callofduty.shooter",
                nowMs = t0 + 60_000L,
                lastActiveGamePackage = "com.motorola.launcher3",
                cooldownTimestamps = cooldowns
            )
        )
        assertTrue(
            AutoPilotManager.shouldTriggerGameSession(
                packageName = "com.activision.callofduty.shooter",
                nowMs = t0 + AutoPilotManager.GAME_SESSION_COOLDOWN_MS + 1L,
                lastActiveGamePackage = "com.motorola.launcher3",
                cooldownTimestamps = cooldowns
            )
        )

        val universal144 = AutoPilotManager.resolvePresetForPackage(
            packageName = "com.indie.fps.arena",
            hardwareTier = HardwareTier.HIGH_FLAGSHIP,
            maxRefreshHz = 144f,
            isSystemCategorizedGame = true,
            appLabel = "Indie Arena",
            observedCaptureFps = 60f
        )
        assertNotNull(universal144)
        assertEquals(InterpolationMode.COMPETITIVE_60_120, universal144!!.mode)
        assertEquals(144, universal144.targetFps)

        val universal30 = AutoPilotManager.resolvePresetForPackage(
            packageName = "com.indie.openworld.rpg",
            hardwareTier = HardwareTier.MID_RANGE_120HZ,
            maxRefreshHz = 120f,
            isSystemCategorizedGame = true,
            appLabel = "Open World RPG",
            observedCaptureFps = 30f
        )
        assertNotNull(universal30)
        assertEquals(InterpolationMode.CONSOLE_30_60, universal30!!.mode)
        assertEquals(60, universal30.targetFps)
    }
}
