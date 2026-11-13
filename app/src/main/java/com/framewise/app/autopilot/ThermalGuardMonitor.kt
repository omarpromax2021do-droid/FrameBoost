package com.framewise.app.autopilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ThermalSeverity {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

data class ThermalGuardState(
    val severity: ThermalSeverity = ThermalSeverity.NONE,
    val batteryTempCelsius: Float = 32.0f,
    val isThrottled: Boolean = false,
    val effectiveMotionScalePercent: Int = 40,
    val forceCasOnly: Boolean = false,
    val casSharpnessActive: Boolean = true,
    val hudWarningBadge: String? = null
)

class ThermalGuardMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var guardEnabled: Boolean = true
    private var requestedScalePercent: Int = 40
    private var lastPowerThermalStatus: Int = 0
    private var lastBatteryTempC: Float = 32.0f
    private var registered = false

    private val _state = MutableStateFlow(ThermalGuardState())
    val state: StateFlow<ThermalGuardState> = _state.asStateFlow()

    private val thermalListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            lastPowerThermalStatus = status
            recomputeState()
        }
    } else {
        null
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (tenths > 0) {
                lastBatteryTempC = tenths / 10f
                recomputeState()
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true

        val stickyBattery = ContextCompat.registerReceiver(
            appContext,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        stickyBattery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { tenths ->
            if (tenths > 0) lastBatteryTempC = tenths / 10f
        }

        val pm = powerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            lastPowerThermalStatus = runCatching { pm.currentThermalStatus }.getOrDefault(0)
            val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener
            if (listener != null) {
                runCatching { pm.addThermalStatusListener(listener) }
            }
        }
        recomputeState()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        val pm = powerManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        runCatching { pm.removeThermalStatusListener(listener) }
    }

    fun updatePolicy(enabled: Boolean, userRequestedScalePercent: Int) {
        guardEnabled = enabled
        requestedScalePercent = normalizeScale(userRequestedScalePercent)
        recomputeState()
    }

    private fun recomputeState() {
        _state.value = evaluate(
            enabled = guardEnabled,
            powerThermalStatus = lastPowerThermalStatus,
            batteryTempCelsius = lastBatteryTempC,
            userRequestedScalePercent = requestedScalePercent
        )
    }

    companion object {
        fun evaluate(
            enabled: Boolean,
            powerThermalStatus: Int,
            batteryTempCelsius: Float,
            userRequestedScalePercent: Int
        ): ThermalGuardState {
            val cleanRequested = normalizeScale(userRequestedScalePercent)
            val apiSeverity = when {
                powerThermalStatus >= 4 -> ThermalSeverity.CRITICAL
                powerThermalStatus == 3 -> ThermalSeverity.SEVERE
                powerThermalStatus == 2 -> ThermalSeverity.MODERATE
                powerThermalStatus == 1 -> ThermalSeverity.LIGHT
                else -> ThermalSeverity.NONE
            }
            val tempSeverity = when {
                batteryTempCelsius >= 45.0f -> ThermalSeverity.SEVERE
                batteryTempCelsius >= 42.0f -> ThermalSeverity.MODERATE
                batteryTempCelsius >= 39.5f -> ThermalSeverity.LIGHT
                else -> ThermalSeverity.NONE
            }
            val effectiveSeverity = if (apiSeverity.ordinal >= tempSeverity.ordinal) apiSeverity else tempSeverity

            if (!enabled || effectiveSeverity == ThermalSeverity.NONE || effectiveSeverity == ThermalSeverity.LIGHT) {
                return ThermalGuardState(
                    severity = effectiveSeverity,
                    batteryTempCelsius = batteryTempCelsius,
                    isThrottled = false,
                    effectiveMotionScalePercent = cleanRequested,
                    forceCasOnly = false,
                    casSharpnessActive = true,
                    hudWarningBadge = null
                )
            }

            if (effectiveSeverity == ThermalSeverity.MODERATE) {
                val (steppedScale, casOnly) = when (cleanRequested) {
                    60 -> 40 to false
                    40 -> 25 to false
                    else -> 25 to true
                }
                val badge = if (casOnly) {
                    "THERMAL: CAS ONLY (${String.format(Locale.US, "%.1f", batteryTempCelsius)}°C)"
                } else {
                    "THERMAL: $steppedScale% (${String.format(Locale.US, "%.1f", batteryTempCelsius)}°C)"
                }
                return ThermalGuardState(
                    severity = effectiveSeverity,
                    batteryTempCelsius = batteryTempCelsius,
                    isThrottled = true,
                    effectiveMotionScalePercent = steppedScale,
                    forceCasOnly = casOnly,
                    casSharpnessActive = true,
                    hudWarningBadge = badge
                )
            }

            // SEVERE or CRITICAL: step down two levels (60 -> 25, 40/25 -> CAS_ONLY) keeping CAS active.
            val (steppedScale, casOnly) = if (cleanRequested >= 60 && effectiveSeverity == ThermalSeverity.SEVERE) {
                25 to false
            } else {
                25 to true
            }
            val badge = "THERMAL HOT: ${if (casOnly) "CAS ONLY" else "25%"} (${
                String.format(Locale.US, "%.1f", batteryTempCelsius)
            }°C)"
            return ThermalGuardState(
                severity = effectiveSeverity,
                batteryTempCelsius = batteryTempCelsius,
                isThrottled = true,
                effectiveMotionScalePercent = steppedScale,
                forceCasOnly = casOnly,
                casSharpnessActive = true,
                hudWarningBadge = badge
            )
        }

        private fun normalizeScale(scale: Int): Int = when (scale) {
            25, 40, 60 -> scale
            else -> 40
        }
    }
}
