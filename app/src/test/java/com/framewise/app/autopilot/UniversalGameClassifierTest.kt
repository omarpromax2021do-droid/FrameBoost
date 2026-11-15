package com.framewise.app.autopilot

import com.framewise.app.device.HardwareTier
import com.framewise.app.device.InterpolationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalGameClassifierTest {

    @Test
    fun classifyCadence_andKnownTokens_cover30AndHighFpsGames() {
        assertTrue(UniversalGameClassifier.hasKnownGamePackageToken("com.activision.callofduty.shooter"))
        assertTrue(UniversalGameClassifier.hasKnownGamePackageToken("com.miHoYo.GenshinImpact"))
        assertFalse(UniversalGameClassifier.hasKnownGamePackageToken("com.whatsapp"))

        assertEquals(
            GameCadenceClass.CONSOLE_30_FPS,
            UniversalGameClassifier.classifyCadence("com.rockstargames.rdr", observedCaptureFps = 0f)
        )
        assertEquals(
            GameCadenceClass.CONSOLE_30_FPS,
            UniversalGameClassifier.classifyCadence("com.indie.story.rpg", observedCaptureFps = 30f)
        )
        assertEquals(
            GameCadenceClass.COMPETITIVE_HIGH_FPS,
            UniversalGameClassifier.classifyCadence("com.dts.freefireth", observedCaptureFps = 60f)
        )
    }

    @Test
    fun resolvePresetAndSessionCooldown_applyUniversalPresetsWithoutSpam() {
        val console30 = AutoPilotManager.resolvePresetForPackage(
            packageName = "com.indie.console.action",
            hardwareTier = HardwareTier.HIGH_FLAGSHIP,
            maxRefreshHz = 120f,
            isSystemCategorizedGame = true,
            appLabel = "Console Action",
            observedCaptureFps = 29.8f
        )
        assertNotNull(console30)
        assertEquals(InterpolationMode.CONSOLE_30_60, console30!!.mode)
        assertEquals(25, console30.motionScalePercent)
        assertEquals(0.80f, console30.casSharpness, 0.001f)
        assertEquals(60, console30.targetFps)

        val competitive144 = AutoPilotManager.resolvePresetForPackage(
            packageName = "com.indie.fast.arena",
            hardwareTier = HardwareTier.HIGH_FLAGSHIP,
            maxRefreshHz = 144f,
            isSystemCategorizedGame = true,
            appLabel = "Fast Arena",
            observedCaptureFps = 60f
        )
        assertNotNull(competitive144)
        assertEquals(InterpolationMode.COMPETITIVE_60_120, competitive144!!.mode)
        assertEquals(40, competitive144.motionScalePercent)
        assertEquals(144, competitive144.targetFps)

        val t0 = 5_000_000L
        val timestamps = mapOf("com.indie.fast.arena" to t0)
        assertFalse(
            AutoPilotManager.shouldTriggerGameSession(
                packageName = "com.indie.fast.arena",
                nowMs = t0 + 15_000L,
                lastActiveGamePackage = "com.indie.fast.arena",
                cooldownTimestamps = timestamps
            )
        )
        assertTrue(
            AutoPilotManager.shouldTriggerGameSession(
                packageName = "com.indie.fast.arena",
                nowMs = t0 + AutoPilotManager.GAME_SESSION_COOLDOWN_MS + 1L,
                lastActiveGamePackage = "com.motorola.launcher3",
                cooldownTimestamps = timestamps
            )
        )
    }
}
