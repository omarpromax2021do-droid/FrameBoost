package com.framewise.app.autopilot

import com.framewise.app.device.InterpolationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileRepositoryTest {

    @Test
    fun builtInProfiles_containsExpectedCodmAndRdr1Entries() {
        val codm = GameProfileRepository.findProfile("com.activision.callofduty.shooter")
        val rdr1 = GameProfileRepository.findProfile("com.netflix.NGP.Kamo")
        assertNotNull(codm)
        assertNotNull(rdr1)
        assertEquals(GameProfileCategory.COD_MOBILE, codm!!.category)
        assertEquals(GameProfileCategory.RDR1_HEAVY_AAA, rdr1!!.category)
    }

    @Test
    fun customProfilesJsonRoundTrip_preservesFieldsAndOverridesBuiltIn() {
        val list = listOf(
            GameProfile(
                packageName = "com.example.racing",
                displayName = "Racing Pro",
                category = GameProfileCategory.CUSTOM,
                preferredMode = InterpolationMode.COMPETITIVE_60_120,
                preferredMotionScale = 60,
                preferredCasSharpness = 0.78f,
                hudMaskEnabled = false
            )
        )
        val json = GameProfileRepository.customProfilesToJson(list)
        val parsed = GameProfileRepository.customProfilesFromJson(json)
        assertEquals(1, parsed.size)
        assertEquals(list[0], parsed[0])
    }

    @Test
    fun hudProtectionZones_convertsTopDownRectsToGlUvAndDisablesCleanly() {
        val config = HudProtectionZonesConfig(
            crosshairEnabled = true,
            crosshair = NormalizedRect(0.44f, 0.44f, 0.56f, 0.56f),
            minimapEnabled = true,
            minimap = NormalizedRect(0.0f, 0.0f, 0.22f, 0.28f),
            leftControlsEnabled = false,
            rightControlsEnabled = true,
            rightControls = NormalizedRect(0.70f, 0.62f, 1.0f, 1.0f)
        )

        val uv = config.toGlUvFloatArray()
        assertEquals(16, uv.size)
        // Crosshair Y stays 0.44..0.56 after 1-y flip
        assertEquals(0.44f, uv[0], 0.001f)
        assertEquals(0.44f, uv[1], 0.001f)
        assertEquals(0.56f, uv[2], 0.001f)
        assertEquals(0.56f, uv[3], 0.001f)

        // Screen top-left minimap (y: 0.0..0.28) maps to GL UV bottom-up y: 0.72..1.00
        assertEquals(0.0f, uv[4], 0.001f)
        assertEquals(0.72f, uv[5], 0.001f)
        assertEquals(0.22f, uv[6], 0.001f)
        assertEquals(1.0f, uv[7], 0.001f)

        // Disabled left controls emit minX > maxX so shader check never triggers
        assertTrue(uv[8] > uv[10])

        // JSON round-trip preserves toggles
        val restored = HudProtectionZonesConfig.fromJson(HudProtectionZonesConfig.toJson(config))
        assertEquals(config.crosshairEnabled, restored.crosshairEnabled)
        assertEquals(config.leftControlsEnabled, restored.leftControlsEnabled)
    }
}
