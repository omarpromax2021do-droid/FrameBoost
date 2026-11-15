package com.framewise.app.autopilot

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object OemBoosterSuppressor {

    val KNOWN_OEM_BOOSTER_PACKAGES: Set<String> = linkedSetOf(
        // Realme / Oppo / OnePlus (Realme UI / ColorOS / OxygenOS)
        "com.coloros.gamespace",
        "com.coloros.gamespaceui",
        "com.oplus.games",
        "com.oplus.cosa",
        "com.realme.gamespace",
        "com.heytap.gamespace",
        "com.oneplus.gamespace",
        // Xiaomi / Redmi / POCO (MIUI / HyperOS)
        "com.xiaomi.joyose",
        "com.miui.powerkeeper",
        "com.miui.securitycenter",
        "com.miui.vpnsdkmanager",
        // Samsung (One UI GOS / Game Booster)
        "com.samsung.android.game.gos",
        "com.samsung.android.game.gametools",
        "com.samsung.android.game.gamehome",
        "com.sec.android.sdhms",
        // Google Pixel
        "com.google.android.gms.gamedashboard",
        "com.google.android.settings.intelligence",
        // Motorola / Lenovo
        "com.motorola.gamemode",
        "com.motorola.gametime",
        "com.motorola.actions",
        "com.motorola.PerformanceManager",
        "com.lenovo.gamestate",
        // Vivo / iQOO / Transsion / Huawei / Honor / Asus / Nubia
        "com.vivo.game",
        "com.vivo.pem",
        "com.transsion.gamespace",
        "com.huawei.gameassistant",
        "com.hihonor.gameassistant",
        "com.asus.gamewidget",
        "cn.nubia.gamelauncher"
    )

    private val knownPackagesLower: Set<String> =
        KNOWN_OEM_BOOSTER_PACKAGES.mapTo(HashSet(KNOWN_OEM_BOOSTER_PACKAGES.size)) { it.lowercase() }

    private val OEM_BOOSTER_REGEX = Regex(
        ".*(gamespace|gamemode|gametime|gameturbo|gamebooster|gameassistant|gamedashboard|joyose|powerkeeper|\\.gos).*",
        RegexOption.IGNORE_CASE
    )

    fun isOemBoosterPackage(
        packageName: String,
        ownPackageName: String = "com.framewise.app",
        activeGamePackage: String? = null
    ): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.equals(ownPackageName, ignoreCase = true)) return false
        if (!activeGamePackage.isNullOrBlank() && trimmed.equals(activeGamePackage, ignoreCase = true)) {
            return false
        }
        return trimmed.lowercase() in knownPackagesLower || OEM_BOOSTER_REGEX.matches(trimmed)
    }

    fun resolveSuppressionTargets(
        installedPackages: Iterable<String>,
        ownPackageName: String = "com.framewise.app",
        activeGamePackage: String? = null
    ): Set<String> {
        val targets = LinkedHashSet<String>(KNOWN_OEM_BOOSTER_PACKAGES.size + 8)
        for (known in KNOWN_OEM_BOOSTER_PACKAGES) {
            if (isOemBoosterPackage(known, ownPackageName, activeGamePackage)) {
                targets.add(known)
            }
        }
        for (installed in installedPackages) {
            if (isOemBoosterPackage(installed, ownPackageName, activeGamePackage)) {
                targets.add(installed)
            }
        }
        return targets
    }

    fun suppressCompetingBoosters(
        context: Context,
        activeGamePackage: String? = null
    ): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val installedNames = queryInstalledPackages(context)
        val targets = resolveSuppressionTargets(
            installedPackages = installedNames,
            ownPackageName = context.packageName,
            activeGamePackage = activeGamePackage
        )
        var suppressed = 0
        for (pkg in targets) {
            // OEM game daemons deprioritize external VirtualDisplay/EGL contexts unless trimmed repeatedly.
            val killed = runCatching {
                am.killBackgroundProcesses(pkg)
                true
            }.getOrDefault(false)
            if (killed) suppressed++
        }
        return suppressed
    }

    private fun queryInstalledPackages(context: Context): List<String> {
        val pm = context.packageManager
        val apps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        }.getOrNull().orEmpty()
        return apps.mapNotNull { it.packageName?.takeIf(String::isNotBlank) }
    }
}
