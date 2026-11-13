package com.framewise.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framewise.app.device.DeviceHardwareProfile
import com.framewise.app.device.GpuCalibrationResult
import com.framewise.app.device.HardwareProfiler
import com.framewise.app.device.InterpolationMode
import com.framewise.app.settings.AppSettings
import com.framewise.app.settings.LocaleHelper
import com.framewise.app.settings.SettingsRepository
import com.framewise.app.updater.DownloadOutcome
import com.framewise.app.updater.GitHubReleaseUpdater
import com.framewise.app.updater.InstallOutcome
import com.framewise.app.updater.ReleaseInfo
import com.framewise.app.updater.UpdateCheckOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface CalibrationUiState {
    data object Idle : CalibrationUiState
    data class Running(val progress: Float) : CalibrationUiState
    data class Completed(val result: GpuCalibrationResult) : CalibrationUiState
    data class Failed(val error: String) : CalibrationUiState
}

sealed interface UpdaterUiState {
    data object Idle : UpdaterUiState
    data object Checking : UpdaterUiState
    data class UpToDate(val currentVersion: String) : UpdaterUiState
    data class Available(val release: ReleaseInfo) : UpdaterUiState
    data class Downloading(val release: ReleaseInfo, val progressPercent: Int) : UpdaterUiState
    data class Verified(val release: ReleaseInfo, val apkFile: File, val sha256Hex: String) : UpdaterUiState
    data object NeedsInstallPermission : UpdaterUiState
    data object RateLimited : UpdaterUiState
    data class Error(val message: String) : UpdaterUiState
}

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val hardwareProfile: DeviceHardwareProfile? = null,
    val calibrationState: CalibrationUiState = CalibrationUiState.Idle,
    val updaterState: UpdaterUiState = UpdaterUiState.Idle,
    val isLoading: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val hardwareProfiler = HardwareProfiler(application)
    private val releaseUpdater = GitHubReleaseUpdater(application, settingsRepository)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var startupUpdateChecked = false

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLoading = false) }
                refreshHardwareProfile(settings.lastCalibrationMs)
                if (!startupUpdateChecked && settings.checkUpdatesOnStartup && settings.onboardingCompleted) {
                    startupUpdateChecked = true
                    checkForUpdates(forceRefresh = false)
                }
            }
        }
    }

    fun refreshHardwareProfile(lastCalibrationMs: Double? = _uiState.value.settings.lastCalibrationMs) {
        viewModelScope.launch {
            val profile = hardwareProfiler.collectProfile(lastCalibrationMs)
            _uiState.update { it.copy(hardwareProfile = profile) }
        }
    }

    fun setLanguage(languageTag: String) {
        viewModelScope.launch {
            settingsRepository.setLanguage(languageTag)
            LocaleHelper.applyLanguage(languageTag)
        }
    }

    fun toggleLanguage() {
        val next = if (_uiState.value.settings.languageTag.startsWith("ar")) "en" else "ar"
        setLanguage(next)
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
        }
    }

    fun reopenOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(false)
        }
    }

    fun setInterpolationMode(mode: InterpolationMode) {
        viewModelScope.launch {
            val profile = _uiState.value.hardwareProfile
            val allowedFps = profile?.allowedTargetFpsOptions() ?: listOf(60)
            val desiredFps = when (mode) {
                InterpolationMode.COMPETITIVE_60_120 -> allowedFps.lastOrNull() ?: 60
                InterpolationMode.CONSOLE_30_60,
                InterpolationMode.STABILIZER_60_CAS -> 60
            }
            val modeId = profile?.preferredModeIdForTargetFps(desiredFps) ?: 0
            settingsRepository.setInterpolationMode(mode)
            settingsRepository.setTargetFps(desiredFps, modeId)
        }
    }

    fun setMotionScalePercent(scale: Int) {
        viewModelScope.launch {
            settingsRepository.setMotionScalePercent(scale)
        }
    }

    fun setCasSharpness(strength: Float) {
        viewModelScope.launch {
            settingsRepository.setCasSharpness(strength)
        }
    }

    fun setTargetFps(targetFps: Int) {
        viewModelScope.launch {
            val profile = _uiState.value.hardwareProfile
            val allowed = profile?.allowedTargetFpsOptions() ?: listOf(60)
            val safeTarget = if (targetFps in allowed) targetFps else (allowed.lastOrNull() ?: 60)
            val modeId = profile?.preferredModeIdForTargetFps(safeTarget) ?: 0
            settingsRepository.setTargetFps(safeTarget, modeId)
        }
    }

    fun setAutoPilotEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoPilotEnabled(enabled) }
    }

    fun setHudMaskEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHudMaskEnabled(enabled) }
    }

    fun setThermalGuardEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setThermalGuardEnabled(enabled) }
    }

    fun setCheckUpdatesOnStartup(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCheckUpdatesOnStartup(enabled) }
    }

    fun runGpuCalibration() {
        if (_uiState.value.calibrationState is CalibrationUiState.Running) return
        viewModelScope.launch {
            _uiState.update { it.copy(calibrationState = CalibrationUiState.Running(0f)) }
            try {
                val result = hardwareProfiler.runThreeSecondGpuCalibration { progress ->
                    _uiState.update { it.copy(calibrationState = CalibrationUiState.Running(progress)) }
                }
                val profile = hardwareProfiler.collectProfile(result.averagePassMs)
                val preferredModeId = profile.preferredModeIdForTargetFps(result.recommendedTargetFps) ?: 0
                settingsRepository.applyCalibrationResult(result, preferredModeId)
                _uiState.update {
                    it.copy(
                        hardwareProfile = profile,
                        calibrationState = CalibrationUiState.Completed(result)
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(calibrationState = CalibrationUiState.Failed(t.message ?: "Calibration error"))
                }
            }
        }
    }

    fun checkForUpdates(forceRefresh: Boolean = true) {
        if (_uiState.value.updaterState is UpdaterUiState.Checking ||
            _uiState.value.updaterState is UpdaterUiState.Downloading
        ) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(updaterState = UpdaterUiState.Checking) }
            val outcome = releaseUpdater.checkForUpdate(
                currentVersion = BuildConfig.VERSION_NAME,
                forceRefresh = forceRefresh
            )
            val mapped = when (outcome) {
                is UpdateCheckOutcome.UpdateAvailable -> UpdaterUiState.Available(outcome.release)
                is UpdateCheckOutcome.UpToDate -> UpdaterUiState.UpToDate(outcome.currentVersion)
                UpdateCheckOutcome.RateLimited -> UpdaterUiState.RateLimited
                is UpdateCheckOutcome.Error -> UpdaterUiState.Error(outcome.message)
            }
            _uiState.update { it.copy(updaterState = mapped) }
        }
    }

    fun downloadAndVerifyUpdate(release: ReleaseInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(updaterState = UpdaterUiState.Downloading(release, 0)) }
            val outcome = releaseUpdater.downloadAndVerifyApk(release) { pct ->
                _uiState.update { it.copy(updaterState = UpdaterUiState.Downloading(release, pct)) }
            }
            val mapped = when (outcome) {
                is DownloadOutcome.Verified -> UpdaterUiState.Verified(release, outcome.apkFile, outcome.sha256Hex)
                is DownloadOutcome.ChecksumMismatch -> UpdaterUiState.Error(
                    "SHA-256 mismatch (expected ${outcome.expected.take(12)}…, got ${outcome.actual.take(12)}…)"
                )

                is DownloadOutcome.Error -> UpdaterUiState.Error(outcome.message)
            }
            _uiState.update { it.copy(updaterState = mapped) }
        }
    }

    fun installVerifiedUpdate(apkFile: File) {
        when (val result = releaseUpdater.installApk(apkFile)) {
            InstallOutcome.SessionStarted -> Unit
            InstallOutcome.NeedsInstallPermission -> {
                _uiState.update { it.copy(updaterState = UpdaterUiState.NeedsInstallPermission) }
            }

            is InstallOutcome.Error -> {
                _uiState.update { it.copy(updaterState = UpdaterUiState.Error(result.message)) }
            }
        }
    }

    fun openInstallPermissionSettings() {
        releaseUpdater.openInstallPermissionSettings()
    }

    fun buildCompatibilityReport(): String? {
        val profile = _uiState.value.hardwareProfile ?: return null
        return profile.toMarkdownCompatibilityReport(
            appName = getApplication<Application>().getString(R.string.app_name),
            versionName = BuildConfig.VERSION_NAME
        )
    }

    suspend fun currentSettingsSnapshot(): AppSettings {
        return settingsRepository.settingsFlow.first()
    }
}
