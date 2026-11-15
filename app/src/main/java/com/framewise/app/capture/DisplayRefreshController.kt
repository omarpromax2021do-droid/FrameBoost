package com.framewise.app.capture

import android.content.Context
import android.provider.Settings

object DisplayRefreshController {

    private val SYSTEM_REFRESH_KEYS = arrayOf(
        "min_refresh_rate",
        "peak_refresh_rate",
        "user_refresh_rate"
    )

    private val lock = Any()
    private var hasBackup = false
    private val backedUpValues = HashMap<String, String?>(3)

    fun canWriteSystemSettings(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }

    fun applyTargetRefreshRate(context: Context, targetHz: Float) {
        if (!canWriteSystemSettings(context)) return
        val resolver = context.applicationContext.contentResolver
        val clampedHz = targetHz.coerceAtLeast(60f)

        synchronized(lock) {
            if (!hasBackup) {
                for (key in SYSTEM_REFRESH_KEYS) {
                    backedUpValues[key] = runCatching { Settings.System.getString(resolver, key) }.getOrNull()
                }
                hasBackup = true
            }
            for (key in SYSTEM_REFRESH_KEYS) {
                runCatching { Settings.System.putFloat(resolver, key, clampedHz) }
            }
        }
    }

    fun restoreOriginalRefreshRate(context: Context) {
        if (!canWriteSystemSettings(context)) return
        val resolver = context.applicationContext.contentResolver

        synchronized(lock) {
            if (!hasBackup) return
            for (key in SYSTEM_REFRESH_KEYS) {
                val original = backedUpValues[key]
                if (original != null) {
                    runCatching { Settings.System.putString(resolver, key, original) }
                } else {
                    val deleted = runCatching {
                        resolver.delete(Settings.System.getUriFor(key), null, null) > 0
                    }.getOrDefault(false)
                    if (!deleted) {
                        runCatching { Settings.System.putFloat(resolver, key, 60.0f) }
                    }
                }
            }
            backedUpValues.clear()
            hasBackup = false
        }
    }
}
