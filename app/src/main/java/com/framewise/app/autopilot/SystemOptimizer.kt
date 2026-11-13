package com.framewise.app.autopilot

import android.app.ActivityManager
import android.app.GameManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.framewise.app.R

data class BoostReport(
    val freedRamMb: Long,
    val availableRamMbBefore: Long,
    val availableRamMbAfter: Long,
    val killedPackagesCount: Int,
    val gameModeLabel: String,
    val timestampMs: Long
)

object SystemOptimizer {
    private val PROTECTED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.google.android.gms"
    )

    fun optimizeForGame(context: Context, targetPackage: String? = null): BoostReport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val beforeMb = readAvailableMemoryMb(am)
        var killedCount = 0

        if (am != null) {
            val candidatePackages = collectCleanablePackages(context, am, targetPackage)
            for (pkg in candidatePackages) {
                val cleared = runCatching {
                    am.killBackgroundProcesses(pkg)
                    true
                }.getOrDefault(false)
                if (cleared) killedCount++
            }
        }

        val afterMb = readAvailableMemoryMb(am)
        val freedMb = (afterMb - beforeMb).coerceAtLeast(0L)
        val gameModeLabel = queryGameModeLabel(context)

        return BoostReport(
            freedRamMb = freedMb,
            availableRamMbBefore = beforeMb,
            availableRamMbAfter = afterMb,
            killedPackagesCount = killedCount,
            gameModeLabel = gameModeLabel,
            timestampMs = System.currentTimeMillis()
        )
    }

    fun boostAndLaunchGame(context: Context, packageName: String?): BoostReport {
        val report = optimizeForGame(context, targetPackage = packageName)
        val msg = context.getString(
            R.string.boost_toast_format,
            report.freedRamMb,
            report.availableRamMbAfter,
            report.gameModeLabel
        )
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

        if (!packageName.isNullOrBlank()) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                runCatching { context.startActivity(launchIntent) }
            }
        }
        return report
    }

    fun queryGameModeLabel(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return "Standard (API <31)"
        }
        val gm = runCatching { context.getSystemService(GameManager::class.java) }.getOrNull()
            ?: return "Unavailable"
        return when (gm.gameMode) {
            GameManager.GAME_MODE_PERFORMANCE -> "Performance"
            GameManager.GAME_MODE_STANDARD -> "Standard"
            GameManager.GAME_MODE_BATTERY -> "Battery Saver"
            GameManager.GAME_MODE_UNSUPPORTED -> "Unsupported"
            else -> "Custom (${gm.gameMode})"
        }
    }

    private fun readAvailableMemoryMb(am: ActivityManager?): Long {
        if (am == null) return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private fun collectCleanablePackages(
        context: Context,
        am: ActivityManager,
        targetPackage: String?
    ): Set<String> {
        val candidates = LinkedHashSet<String>()

        @Suppress("DEPRECATION")
        val running = runCatching { am.runningAppProcesses }.getOrNull().orEmpty()
        for (proc in running) {
            if (proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                proc.pkgList?.forEach { pkg ->
                    if (!isProtectedPackage(pkg, context.packageName, targetPackage)) {
                        candidates.add(pkg)
                    }
                }
            }
        }

        // Also request background trim for cached launcher packages not actively targeted.
        GameProfileRepository.listInstalledLaunchableApps(context).forEach { app ->
            if (!isProtectedPackage(app.packageName, context.packageName, targetPackage)) {
                candidates.add(app.packageName)
            }
        }
        return candidates
    }

    private fun isProtectedPackage(
        pkg: String,
        ownPackage: String,
        targetPackage: String?
    ): Boolean {
        if (pkg.isBlank() || pkg == ownPackage || pkg == targetPackage) return true
        if (pkg in PROTECTED_PACKAGES) return true
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("launcher")
    }
}
