package com.framewise.app.gpu

import com.framewise.app.device.InterpolationMode

data class SmartGateDecision(
    val bypassOpticalFlow: Boolean,
    val effectiveMotionScalePercent: Int,
    val statusReason: String
)

class SmartGateController(
    private val downgradeHoldNs: Long = DEFAULT_DOWNGRADE_HOLD_NS,
    private val restoreHoldNs: Long = DEFAULT_RESTORE_HOLD_NS
) {
    private var lastRequestedMode: InterpolationMode = InterpolationMode.CONSOLE_30_60
    private var lastRequestedScale: Int = 40
    private var currentScale: Int = 40
    private var lowFpsFallback: Boolean = false
    private var gpuCasOnlyFallback: Boolean = false
    private var unhealthySinceNs: Long = 0L
    private var healthySinceNs: Long = 0L

    fun evaluate(
        enabled: Boolean,
        requestedMode: InterpolationMode,
        requestedScalePercent: Int,
        captureFps: Float,
        gpuPassTimeMs: Float,
        nowNs: Long,
        minCaptureFpsOverride: Float? = null
    ): SmartGateDecision {
        val normalizedRequested = normalizeScale(requestedScalePercent)
        if (normalizedRequested != lastRequestedScale || requestedMode != lastRequestedMode) {
            lastRequestedMode = requestedMode
            lastRequestedScale = normalizedRequested
            resetDegradationState(normalizedRequested)
        }

        if (!enabled) {
            resetDegradationState(normalizedRequested)
            return SmartGateDecision(
                bypassOpticalFlow = requestedMode == InterpolationMode.STABILIZER_60_CAS,
                effectiveMotionScalePercent = normalizedRequested,
                statusReason = "SmartGate Off"
            )
        }

        if (requestedMode == InterpolationMode.STABILIZER_60_CAS) {
            resetDegradationState(normalizedRequested)
            return SmartGateDecision(
                bypassOpticalFlow = true,
                effectiveMotionScalePercent = normalizedRequested,
                statusReason = "60Hz Stabilizer + CAS"
            )
        }

        val minCaptureFps = minCaptureFpsOverride ?: if (requestedMode == InterpolationMode.CONSOLE_30_60) 24f else 40f
        val maxGpuBudgetMs = if (requestedMode == InterpolationMode.CONSOLE_30_60) 10.0f else 5.5f

        val isLowFps = captureFps > 0f && captureFps < minCaptureFps
        val isGpuOverBudget = gpuPassTimeMs > maxGpuBudgetMs

        if (restoreHoldNs <= 0L && captureFps >= minCaptureFps) {
            lowFpsFallback = false
        }

        if (isLowFps || isGpuOverBudget) {
            healthySinceNs = 0L
            if (!isLowFps && captureFps >= minCaptureFps) {
                lowFpsFallback = false
            }
            if (unhealthySinceNs == 0L) {
                unhealthySinceNs = nowNs
            }
            if (nowNs - unhealthySinceNs >= downgradeHoldNs) {
                if (isLowFps) {
                    lowFpsFallback = true
                } else {
                    stepDownForGpuBudget()
                }
                unhealthySinceNs = nowNs
            }
        } else {
            unhealthySinceNs = 0L
            val isDegraded = lowFpsFallback || gpuCasOnlyFallback || currentScale < normalizedRequested
            val isRecovered = captureFps >= minCaptureFps && gpuPassTimeMs <= maxGpuBudgetMs
            if (isDegraded && isRecovered) {
                if (healthySinceNs == 0L) {
                    healthySinceNs = nowNs
                }
                if (restoreHoldNs <= 0L || nowNs - healthySinceNs >= restoreHoldNs) {
                    lowFpsFallback = false
                    gpuCasOnlyFallback = false
                    currentScale = normalizedRequested
                    healthySinceNs = 0L
                }
            } else {
                healthySinceNs = 0L
            }
        }

        return when {
            lowFpsFallback -> SmartGateDecision(
                bypassOpticalFlow = true,
                effectiveMotionScalePercent = currentScale,
                statusReason = "Low Base FPS (<${minCaptureFps.toInt()}) - CAS Only"
            )

            gpuCasOnlyFallback -> SmartGateDecision(
                bypassOpticalFlow = true,
                effectiveMotionScalePercent = currentScale,
                statusReason = "GPU Overload - CAS Only"
            )

            currentScale < normalizedRequested -> SmartGateDecision(
                bypassOpticalFlow = false,
                effectiveMotionScalePercent = currentScale,
                statusReason = "GPU Budget Step-Down ($currentScale%)"
            )

            else -> SmartGateDecision(
                bypassOpticalFlow = false,
                effectiveMotionScalePercent = currentScale,
                statusReason = activeStatusReason(requestedMode)
            )
        }
    }

    fun reset() {
        resetDegradationState(lastRequestedScale)
    }

    private fun stepDownForGpuBudget() {
        when {
            gpuCasOnlyFallback -> Unit
            currentScale >= 60 -> currentScale = 40
            currentScale >= 40 -> currentScale = 25
            else -> gpuCasOnlyFallback = true
        }
    }

    private fun activeStatusReason(mode: InterpolationMode): String = when (mode) {
        InterpolationMode.COMPETITIVE_60_120 -> "Active: 60→120 Optical Flow + CAS"
        InterpolationMode.CONSOLE_30_60 -> "Active: 30→60 Optical Flow + CAS"
        InterpolationMode.STABILIZER_60_CAS -> "60Hz Stabilizer + CAS"
    }

    private fun resetDegradationState(scale: Int) {
        currentScale = scale
        lowFpsFallback = false
        gpuCasOnlyFallback = false
        unhealthySinceNs = 0L
        healthySinceNs = 0L
    }

    private fun normalizeScale(scale: Int): Int = when (scale) {
        25, 40, 60 -> scale
        else -> 40
    }

    companion object {
        const val DEFAULT_DOWNGRADE_HOLD_NS = 1_500_000_000L
        const val DEFAULT_RESTORE_HOLD_NS = 0L
    }
}
