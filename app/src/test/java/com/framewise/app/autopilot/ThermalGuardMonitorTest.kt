package com.framewise.app.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGuardMonitorTest {

    @Test
    fun evaluate_stepsDownOnModerateThermalWhileKeepingCasActive() {
        val stateFrom60 = ThermalGuardMonitor.evaluate(
            enabled = true,
            powerThermalStatus = 2, // THERMAL_STATUS_MODERATE
            batteryTempCelsius = 40.0f,
            userRequestedScalePercent = 60
        )
        assertTrue(stateFrom60.isThrottled)
        assertEquals(40, stateFrom60.effectiveMotionScalePercent)
        assertFalse(stateFrom60.forceCasOnly)
        assertTrue(stateFrom60.casSharpnessActive)
        assertNotNull(stateFrom60.hudWarningBadge)

        val stateFrom40 = ThermalGuardMonitor.evaluate(
            enabled = true,
            powerThermalStatus = 2,
            batteryTempCelsius = 40.0f,
            userRequestedScalePercent = 40
        )
        assertTrue(stateFrom40.isThrottled)
        assertEquals(25, stateFrom40.effectiveMotionScalePercent)
        assertFalse(stateFrom40.forceCasOnly)
        assertTrue(stateFrom40.casSharpnessActive)

        val stateFrom25 = ThermalGuardMonitor.evaluate(
            enabled = true,
            powerThermalStatus = 2,
            batteryTempCelsius = 40.0f,
            userRequestedScalePercent = 25
        )
        assertTrue(stateFrom25.isThrottled)
        assertTrue(stateFrom25.forceCasOnly)
        assertTrue(stateFrom25.casSharpnessActive)
    }

    @Test
    fun evaluate_triggersOnBatteryTempFallbackAndRestoresOnCooldown() {
        val hotBatteryState = ThermalGuardMonitor.evaluate(
            enabled = true,
            powerThermalStatus = 0, // Legacy API 26-28 where PowerManager thermal status is absent
            batteryTempCelsius = 42.5f,
            userRequestedScalePercent = 40
        )
        assertTrue(hotBatteryState.isThrottled)
        assertEquals(ThermalSeverity.MODERATE, hotBatteryState.severity)
        assertEquals(25, hotBatteryState.effectiveMotionScalePercent)
        assertNotNull(hotBatteryState.hudWarningBadge)

        val severeBatteryState = ThermalGuardMonitor.evaluate(
            enabled = true,
            powerThermalStatus = 0,
            batteryTempCelsius = 45.3f,
            userRequestedScalePercent = 40
        )
        assertTrue(severeBatteryState.isThrottled)
        assertTrue(severeBatteryState.forceCasOnly)
        assertTrue(severeBatteryState.casSharpnessActive)

        val cooledState = ThermalGuardMonitor.evaluate(
            enabled = true,
            powerThermalStatus = 1, // LIGHT
            batteryTempCelsius = 38.0f,
            userRequestedScalePercent = 60
        )
        assertFalse(cooledState.isThrottled)
        assertFalse(cooledState.forceCasOnly)
        assertEquals(60, cooledState.effectiveMotionScalePercent)
        assertNull(cooledState.hudWarningBadge)
    }

    @Test
    fun evaluate_whenDisabled_preservesUserRequestedScale() {
        val disabled = ThermalGuardMonitor.evaluate(
            enabled = false,
            powerThermalStatus = 4,
            batteryTempCelsius = 46.0f,
            userRequestedScalePercent = 60
        )
        assertFalse(disabled.isThrottled)
        assertEquals(60, disabled.effectiveMotionScalePercent)
        assertNull(disabled.hudWarningBadge)
    }
}
