package com.framewise.app.gpu

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import androidx.annotation.RequiresApi

class AdpfSessionManager {

    private val lock = Any()

    @Volatile
    private var session: Any? = null

    @Volatile
    private var lastTargetDurationNs: Long = 0L

    fun startSession(context: Context, threadTids: IntArray, targetHz: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val validTids = threadTids.filter { it > 0 }.distinct().toIntArray()
        if (validTids.isEmpty()) return

        val targetNs = computeTargetDurationNanos(targetHz)
        synchronized(lock) {
            closeLocked()
            val created = Api31Impl.createSession(context, validTids, targetNs)
            if (created != null) {
                session = created
                lastTargetDurationNs = targetNs
            }
        }
    }

    fun updateTargetFrameRate(targetHz: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val targetNs = computeTargetDurationNanos(targetHz)
        synchronized(lock) {
            val active = session ?: return
            if (targetNs == lastTargetDurationNs) return
            if (Api31Impl.updateTargetWorkDuration(active, targetNs)) {
                lastTargetDurationNs = targetNs
            }
        }
    }

    fun reportActualWorkDuration(actualDurationNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (actualDurationNanos <= 0L) return
        val active = session ?: return
        Api31Impl.reportActualWorkDuration(active, actualDurationNanos)
    }

    fun close() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        val active = session ?: return
        session = null
        lastTargetDurationNs = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Api31Impl.closeSession(active)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Impl {
        fun createSession(
            context: Context,
            tids: IntArray,
            targetDurationNanos: Long
        ): PerformanceHintManager.Session? {
            // Binds render and capture TIDs to the SoC scheduler hint HAL, bypassing userspace governors.
            val mgr = runCatching {
                context.getSystemService(PerformanceHintManager::class.java)
            }.getOrNull() ?: return null
            return runCatching {
                mgr.createHintSession(tids, targetDurationNanos)
            }.getOrNull()
        }

        fun updateTargetWorkDuration(rawSession: Any, targetDurationNanos: Long): Boolean {
            val hintSession = rawSession as? PerformanceHintManager.Session ?: return false
            return runCatching {
                hintSession.updateTargetWorkDuration(targetDurationNanos)
                true
            }.getOrDefault(false)
        }

        fun reportActualWorkDuration(rawSession: Any, actualDurationNanos: Long) {
            val hintSession = rawSession as? PerformanceHintManager.Session ?: return
            runCatching {
                hintSession.reportActualWorkDuration(actualDurationNanos)
            }
        }

        fun closeSession(rawSession: Any) {
            val hintSession = rawSession as? PerformanceHintManager.Session ?: return
            runCatching { hintSession.close() }
        }
    }

    companion object {
        fun computeTargetDurationNanos(targetHz: Float): Long {
            return (1_000_000_000.0 / targetHz.coerceIn(30f, 240f)).toLong()
        }
    }
}
