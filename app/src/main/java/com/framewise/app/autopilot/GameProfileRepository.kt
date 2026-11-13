package com.framewise.app.autopilot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.framewise.app.device.InterpolationMode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun normalized(): NormalizedRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(l, 1f)
        val b = bottom.coerceIn(t, 1f)
        return NormalizedRect(l, t, r, b)
    }
}

data class HudProtectionZonesConfig(
    val crosshairEnabled: Boolean = true,
    val crosshair: NormalizedRect = NormalizedRect(0.44f, 0.44f, 0.56f, 0.56f),
    val minimapEnabled: Boolean = true,
    val minimap: NormalizedRect = NormalizedRect(0.0f, 0.0f, 0.22f, 0.28f),
    val leftControlsEnabled: Boolean = true,
    val leftControls: NormalizedRect = NormalizedRect(0.0f, 0.62f, 0.30f, 1.0f),
    val rightControlsEnabled: Boolean = true,
    val rightControls: NormalizedRect = NormalizedRect(0.70f, 0.62f, 1.0f, 1.0f)
) {
    /**
     * Converts screen-top-down rects into OpenGL bottom-up UV rects (4 * vec4(minX, minY, maxX, maxY)).
     * Disabled zones emit an inverted X range [2, 1] so shader bounds checks evaluate strictly false.
     */
    fun toGlUvFloatArray(): FloatArray {
        val out = FloatArray(16)
        writeZone(out, 0, crosshairEnabled, crosshair)
        writeZone(out, 4, minimapEnabled, minimap)
        writeZone(out, 8, leftControlsEnabled, leftControls)
        writeZone(out, 12, rightControlsEnabled, rightControls)
        return out
    }

    private fun writeZone(out: FloatArray, offset: Int, enabled: Boolean, rect: NormalizedRect) {
        if (!enabled) {
            out[offset] = 2f
            out[offset + 1] = 2f
            out[offset + 2] = 1f
            out[offset + 3] = 1f
            return
        }
        val clean = rect.normalized()
        out[offset] = clean.left
        out[offset + 1] = 1f - clean.bottom
        out[offset + 2] = clean.right
        out[offset + 3] = 1f - clean.top
    }

    companion object {
        private val gson = Gson()

        fun toJson(config: HudProtectionZonesConfig): String = gson.toJson(config)

        fun fromJson(raw: String?): HudProtectionZonesConfig {
            if (raw.isNullOrBlank()) return HudProtectionZonesConfig()
            return runCatching {
                gson.fromJson(raw, HudProtectionZonesConfig::class.java)
            }.getOrNull() ?: HudProtectionZonesConfig()
        }
    }
}

enum class GameProfileCategory {
    COD_MOBILE,
    RDR1_HEAVY_AAA,
    CUSTOM
}

data class GameProfile(
    val packageName: String,
    val displayName: String,
    val category: GameProfileCategory,
    val preferredMode: InterpolationMode = InterpolationMode.CONSOLE_30_60,
    val preferredMotionScale: Int = 40,
    val preferredCasSharpness: Float = 0.65f,
    val hudMaskEnabled: Boolean = true
)

data class InstalledAppInfo(
    val packageName: String,
    val appLabel: String
)

object GameProfileRepository {
    private val gson = Gson()

    val builtInProfiles: Map<String, GameProfile> = listOf(
        GameProfile(
            packageName = "com.activision.callofduty.shooter",
            displayName = "Call of Duty: Mobile (Global)",
            category = GameProfileCategory.COD_MOBILE,
            preferredMode = InterpolationMode.COMPETITIVE_60_120,
            preferredMotionScale = 40,
            preferredCasSharpness = 0.65f,
            hudMaskEnabled = true
        ),
        GameProfile(
            packageName = "com.garena.game.codm",
            displayName = "Call of Duty: Mobile (Garena)",
            category = GameProfileCategory.COD_MOBILE,
            preferredMode = InterpolationMode.COMPETITIVE_60_120,
            preferredMotionScale = 40,
            preferredCasSharpness = 0.65f,
            hudMaskEnabled = true
        ),
        GameProfile(
            packageName = "com.tencent.tmgp.kr.codm",
            displayName = "Call of Duty: Mobile (KR)",
            category = GameProfileCategory.COD_MOBILE,
            preferredMode = InterpolationMode.COMPETITIVE_60_120,
            preferredMotionScale = 40,
            preferredCasSharpness = 0.65f,
            hudMaskEnabled = true
        ),
        GameProfile(
            packageName = "com.vng.codm",
            displayName = "Call of Duty: Mobile (VNG)",
            category = GameProfileCategory.COD_MOBILE,
            preferredMode = InterpolationMode.COMPETITIVE_60_120,
            preferredMotionScale = 40,
            preferredCasSharpness = 0.65f,
            hudMaskEnabled = true
        ),
        GameProfile(
            packageName = "com.netflix.NGP.Kamo",
            displayName = "Red Dead Redemption (Netflix)",
            category = GameProfileCategory.RDR1_HEAVY_AAA,
            preferredMode = InterpolationMode.CONSOLE_30_60,
            preferredMotionScale = 25,
            preferredCasSharpness = 0.85f,
            hudMaskEnabled = true
        ),
        GameProfile(
            packageName = "com.rockstargames.rdr",
            displayName = "Red Dead Redemption (Rockstar)",
            category = GameProfileCategory.RDR1_HEAVY_AAA,
            preferredMode = InterpolationMode.CONSOLE_30_60,
            preferredMotionScale = 25,
            preferredCasSharpness = 0.85f,
            hudMaskEnabled = true
        ),
        GameProfile(
            packageName = "com.miHoYo.GenshinImpact",
            displayName = "Genshin Impact",
            category = GameProfileCategory.RDR1_HEAVY_AAA,
            preferredMode = InterpolationMode.CONSOLE_30_60,
            preferredMotionScale = 25,
            preferredCasSharpness = 0.85f,
            hudMaskEnabled = true
        )
    ).associateBy { it.packageName }

    fun findProfile(
        packageName: String,
        customProfiles: Map<String, GameProfile> = emptyMap()
    ): GameProfile? {
        if (packageName.isBlank()) return null
        return customProfiles[packageName] ?: builtInProfiles[packageName]
    }

    fun customProfilesToJson(profiles: List<GameProfile>): String = gson.toJson(profiles)

    fun customProfilesFromJson(raw: String?): List<GameProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<GameProfile>>() {}.type
            gson.fromJson<List<GameProfile>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun listInstalledLaunchableApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
        val seen = HashSet<String>()
        return resolveInfos
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName || !seen.add(pkg)) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                InstalledAppInfo(packageName = pkg, appLabel = label)
            }
            .sortedBy { it.appLabel.lowercase() }
    }
}
