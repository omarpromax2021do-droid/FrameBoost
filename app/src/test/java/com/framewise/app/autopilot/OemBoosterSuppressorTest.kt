package com.framewise.app.autopilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemBoosterSuppressorTest {

    @Test
    fun isOemBoosterPackage_matchesMultiVendorBoostersAndRegex() {
        val multiVendorBoosters = listOf(
            "com.coloros.gamespace",
            "com.realme.gamespace",
            "com.oplus.cosa",
            "com.oneplus.gamespace",
            "com.xiaomi.joyose",
            "com.miui.powerkeeper",
            "com.miui.securitycenter",
            "com.samsung.android.game.gos",
            "com.samsung.android.game.gametools",
            "com.sec.android.sdhms",
            "com.google.android.gms.gamedashboard",
            "com.motorola.gamemode",
            "com.motorola.gametime",
            "com.motorola.PerformanceManager",
            "com.vivo.game",
            "com.transsion.gamespace",
            "com.asus.gamewidget",
            "com.vendor.custom.gameturbo.service"
        )

        for (pkg in multiVendorBoosters) {
            assertTrue("Expected OEM package match for $pkg", OemBoosterSuppressor.isOemBoosterPackage(pkg))
        }
    }

    @Test
    fun resolveSuppressionTargets_excludesOwnAppAndActiveGame() {
        val activeGame = "com.activision.callofduty.shooter"
        val customRegexMatchingGame = "com.studio.gamemode.arena"
        val installed = listOf(
            "com.framewise.app",
            activeGame,
            customRegexMatchingGame,
            "com.vendor.custom.gamebooster",
            "com.android.chrome"
        )

        val targets = OemBoosterSuppressor.resolveSuppressionTargets(
            installedPackages = installed,
            ownPackageName = "com.framewise.app",
            activeGamePackage = activeGame
        )

        assertFalse(targets.contains("com.framewise.app"))
        assertFalse(targets.contains(activeGame))
        assertFalse(targets.contains("com.android.chrome"))
        assertTrue(targets.contains("com.vendor.custom.gamebooster"))
        assertTrue(targets.contains("com.samsung.android.game.gos"))
        assertTrue(targets.contains("com.xiaomi.joyose"))

        val activeGameProtected = OemBoosterSuppressor.resolveSuppressionTargets(
            installedPackages = installed,
            ownPackageName = "com.framewise.app",
            activeGamePackage = customRegexMatchingGame
        )
        assertFalse(activeGameProtected.contains(customRegexMatchingGame))
    }
}
