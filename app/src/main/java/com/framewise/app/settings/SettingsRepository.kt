package com.framewise.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.framewise.app.device.GpuCalibrationResult
import com.framewise.app.device.HardwareTier
import com.framewise.app.device.InterpolationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.framewiseDataStore: DataStore<Preferences> by preferencesDataStore(name = "framewise_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val INTERPOLATION_MODE = stringPreferencesKey("interpolation_mode")
        val MOTION_SCALE_PERCENT = intPreferencesKey("motion_scale_percent")
        val CAS_SHARPNESS = floatPreferencesKey("cas_sharpness")
        val TARGET_FPS = intPreferencesKey("target_fps")
        val PREFERRED_DISPLAY_MODE_ID = intPreferencesKey("preferred_display_mode_id")
        val AUTO_PILOT_ENABLED = booleanPreferencesKey("auto_pilot_enabled")
        val HUD_MASK_ENABLED = booleanPreferencesKey("hud_mask_enabled")
        val THERMAL_GUARD_ENABLED = booleanPreferencesKey("thermal_guard_enabled")
        val SMART_GATE_ENABLED = booleanPreferencesKey("smart_gate_enabled")
        val CHECK_UPDATES_ON_STARTUP = booleanPreferencesKey("check_updates_on_startup")
        val LAST_CALIBRATION_MS = doublePreferencesKey("last_calibration_ms")
        val HARDWARE_TIER = stringPreferencesKey("hardware_tier")
        val CACHED_RELEASE_ETAG = stringPreferencesKey("cached_release_etag")
        val CACHED_RELEASE_JSON = stringPreferencesKey("cached_release_json")
    }

    val settingsFlow: Flow<AppSettings> = context.framewiseDataStore.data.map { prefs ->
        AppSettings(
            languageTag = prefs[Keys.LANGUAGE_TAG] ?: "en",
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            interpolationMode = prefs[Keys.INTERPOLATION_MODE]
                ?.let { runCatching { InterpolationMode.valueOf(it) }.getOrNull() }
                ?: InterpolationMode.CONSOLE_30_60,
            motionScalePercent = prefs[Keys.MOTION_SCALE_PERCENT] ?: 40,
            casSharpness = (prefs[Keys.CAS_SHARPNESS] ?: 0.65f).coerceIn(0f, 1f),
            targetFps = prefs[Keys.TARGET_FPS] ?: 60,
            preferredDisplayModeId = prefs[Keys.PREFERRED_DISPLAY_MODE_ID] ?: 0,
            autoPilotEnabled = prefs[Keys.AUTO_PILOT_ENABLED] ?: true,
            hudMaskEnabled = prefs[Keys.HUD_MASK_ENABLED] ?: true,
            thermalGuardEnabled = prefs[Keys.THERMAL_GUARD_ENABLED] ?: true,
            smartGateEnabled = prefs[Keys.SMART_GATE_ENABLED] ?: true,
            checkUpdatesOnStartup = prefs[Keys.CHECK_UPDATES_ON_STARTUP] ?: true,
            lastCalibrationMs = prefs[Keys.LAST_CALIBRATION_MS],
            hardwareTier = prefs[Keys.HARDWARE_TIER]
                ?.let { runCatching { HardwareTier.valueOf(it) }.getOrNull() }
                ?: HardwareTier.MID_RANGE_120HZ,
            cachedReleaseEtag = prefs[Keys.CACHED_RELEASE_ETAG],
            cachedReleaseJson = prefs[Keys.CACHED_RELEASE_JSON]
        )
    }

    suspend fun setLanguage(languageTag: String) {
        val normalized = if (languageTag.startsWith("ar")) "ar" else "en"
        context.framewiseDataStore.edit { it[Keys.LANGUAGE_TAG] = normalized }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.framewiseDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setInterpolationMode(mode: InterpolationMode) {
        context.framewiseDataStore.edit { it[Keys.INTERPOLATION_MODE] = mode.name }
    }

    suspend fun setMotionScalePercent(scale: Int) {
        val clamped = when (scale) {
            25, 40, 60 -> scale
            else -> 40
        }
        context.framewiseDataStore.edit { it[Keys.MOTION_SCALE_PERCENT] = clamped }
    }

    suspend fun setCasSharpness(strength: Float) {
        context.framewiseDataStore.edit { it[Keys.CAS_SHARPNESS] = strength.coerceIn(0f, 1f) }
    }

    suspend fun setTargetFps(targetFps: Int, preferredModeId: Int = 0) {
        context.framewiseDataStore.edit {
            it[Keys.TARGET_FPS] = targetFps
            it[Keys.PREFERRED_DISPLAY_MODE_ID] = preferredModeId
        }
    }

    suspend fun setAutoPilotEnabled(enabled: Boolean) {
        context.framewiseDataStore.edit { it[Keys.AUTO_PILOT_ENABLED] = enabled }
    }

    suspend fun setHudMaskEnabled(enabled: Boolean) {
        context.framewiseDataStore.edit { it[Keys.HUD_MASK_ENABLED] = enabled }
    }

    suspend fun setThermalGuardEnabled(enabled: Boolean) {
        context.framewiseDataStore.edit { it[Keys.THERMAL_GUARD_ENABLED] = enabled }
    }

    suspend fun setSmartGateEnabled(enabled: Boolean) {
        context.framewiseDataStore.edit { it[Keys.SMART_GATE_ENABLED] = enabled }
    }

    suspend fun setCheckUpdatesOnStartup(enabled: Boolean) {
        context.framewiseDataStore.edit { it[Keys.CHECK_UPDATES_ON_STARTUP] = enabled }
    }

    suspend fun applyCalibrationResult(result: GpuCalibrationResult, preferredModeId: Int) {
        context.framewiseDataStore.edit {
            it[Keys.LAST_CALIBRATION_MS] = result.averagePassMs
            it[Keys.HARDWARE_TIER] = result.recommendedTier.name
            it[Keys.INTERPOLATION_MODE] = result.recommendedMode.name
            it[Keys.MOTION_SCALE_PERCENT] = result.recommendedMotionScalePercent
            it[Keys.CAS_SHARPNESS] = result.recommendedCasSharpness
            it[Keys.TARGET_FPS] = result.recommendedTargetFps
            it[Keys.PREFERRED_DISPLAY_MODE_ID] = preferredModeId
        }
    }

    suspend fun cacheReleaseResponse(etag: String?, rawJson: String) {
        context.framewiseDataStore.edit {
            if (!etag.isNullOrBlank()) {
                it[Keys.CACHED_RELEASE_ETAG] = etag
            }
            it[Keys.CACHED_RELEASE_JSON] = rawJson
        }
    }
}
