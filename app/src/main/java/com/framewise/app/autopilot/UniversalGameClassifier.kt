package com.framewise.app.autopilot

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

enum class GameCadenceClass {
    CONSOLE_30_FPS,
    COMPETITIVE_HIGH_FPS
}

data class ClassifiedGame(
    val packageName: String,
    val displayName: String,
    val cadenceClass: GameCadenceClass,
    val isSystemCategorizedGame: Boolean,
    val profile: GameProfile? = null
)

object UniversalGameClassifier {

    private val KNOWN_GAME_PACKAGE_TOKENS = listOf(
        "activision",
        "tencent.ig",
        "tencent.tmgp",
        "pubg",
        "garena",
        "dts.freefire",
        "callofduty",
        "mihoyo",
        "hoyoverse",
        "rockstargames",
        "netflix.ngp",
        "netease",
        "riotgames",
        "epicgames",
        "supercell",
        "roblox",
        "mojang",
        "gameloft",
        "ea.game",
        "unity3d",
        "unreal"
    )

    private val HEAVY_30_FPS_TOKENS = listOf(
        "rdr",
        "rockstargames",
        "netflix.ngp.kamo",
        "genshin",
        "mihoyo",
        "hoyoverse",
        "wuthering",
        "zenless",
        "honkai",
        "alienisolation",
        "residentevil",
        "hitman",
        "grid"
    )

    fun inspectPackage(
        context: Context,
        packageName: String,
        customProfiles: Map<String, GameProfile> = emptyMap(),
        observedCaptureFps: Float = 0f
    ): ClassifiedGame? {
        if (AutoPilotManager.isIgnoredPackage(packageName, context.packageName)) return null

        val profile = GameProfileRepository.findProfile(packageName, customProfiles)
        var isCategoryGame = false
        var resolvedLabel: String? = profile?.displayName

        val pm = context.packageManager
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull()

        if (appInfo != null) {
            @Suppress("DEPRECATION")
            val legacyGameFlag = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val api26CategoryGame = appInfo.category == ApplicationInfo.CATEGORY_GAME
            isCategoryGame = api26CategoryGame || legacyGameFlag
            if (resolvedLabel.isNullOrBlank()) {
                resolvedLabel = pm.getApplicationLabel(appInfo).toString().trim()
            }
        }

        val tokenMatched = hasKnownGamePackageToken(packageName)
        if (profile == null && !isCategoryGame && !tokenMatched) {
            return null
        }

        val cadence = classifyCadence(
            packageName = packageName,
            profile = profile,
            observedCaptureFps = observedCaptureFps
        )

        return ClassifiedGame(
            packageName = packageName,
            displayName = resolvedLabel?.ifBlank { packageName } ?: packageName,
            cadenceClass = cadence,
            isSystemCategorizedGame = isCategoryGame || tokenMatched,
            profile = profile
        )
    }

    fun hasKnownGamePackageToken(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val lower = packageName.lowercase()
        return KNOWN_GAME_PACKAGE_TOKENS.any { lower.contains(it) }
    }

    fun classifyCadence(
        packageName: String,
        profile: GameProfile? = null,
        observedCaptureFps: Float = 0f
    ): GameCadenceClass {
        if (profile != null) {
            return when (profile.category) {
                GameProfileCategory.RDR1_HEAVY_AAA -> GameCadenceClass.CONSOLE_30_FPS
                GameProfileCategory.COD_MOBILE -> GameCadenceClass.COMPETITIVE_HIGH_FPS
                GameProfileCategory.CUSTOM -> {
                    if (profile.preferredMode == com.framewise.app.device.InterpolationMode.CONSOLE_30_60) {
                        GameCadenceClass.CONSOLE_30_FPS
                    } else {
                        GameCadenceClass.COMPETITIVE_HIGH_FPS
                    }
                }
            }
        }

        if (observedCaptureFps in 18f..36f) {
            return GameCadenceClass.CONSOLE_30_FPS
        }

        val lower = packageName.lowercase()
        return if (HEAVY_30_FPS_TOKENS.any { lower.contains(it) }) {
            GameCadenceClass.CONSOLE_30_FPS
        } else {
            GameCadenceClass.COMPETITIVE_HIGH_FPS
        }
    }
}
