package com.framewise.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framewise.app.capture.FrameCaptureService
import com.framewise.app.capture.PipelineSessionState
import com.framewise.app.onboarding.WelcomeOnboardingScreen
import com.framewise.app.settings.LocaleHelper
import com.framewise.app.ui.HomeDashboardScreen
import com.framewise.app.ui.SettingsScreen
import com.framewise.app.ui.theme.FramewiseTheme

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val presetLabel = viewModel.formatPresetLabel()
            viewModel.startCaptureSession(result.resultCode, data, presetLabel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            var showSettings by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(uiState.settings.languageTag) {
                if (!uiState.isLoading) {
                    LocaleHelper.applyLanguage(uiState.settings.languageTag)
                }
            }

            LaunchedEffect(uiState.settings.preferredDisplayModeId) {
                val modeId = uiState.settings.preferredDisplayModeId
                if (modeId > 0) {
                    val params = window.attributes
                    if (params.preferredDisplayModeId != modeId) {
                        params.preferredDisplayModeId = modeId
                        window.attributes = params
                    }
                }
            }

            FramewiseTheme {
                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    !uiState.settings.onboardingCompleted -> {
                        WelcomeOnboardingScreen(
                            uiState = uiState,
                            onToggleLanguage = viewModel::toggleLanguage,
                            onRunCalibration = viewModel::runGpuCalibration,
                            onFinishOnboarding = viewModel::completeOnboarding
                        )
                    }

                    showSettings -> {
                        SettingsScreen(
                            uiState = uiState,
                            onNavigateBack = { showSettings = false },
                            onSelectLanguage = viewModel::setLanguage,
                            onSelectMotionScale = viewModel::setMotionScalePercent,
                            onChangeCasSharpness = viewModel::setCasSharpness,
                            onSelectTargetFps = viewModel::setTargetFps,
                            onToggleAutoPilot = viewModel::setAutoPilotEnabled,
                            onToggleHudMask = viewModel::setHudMaskEnabled,
                            onToggleThermalGuard = viewModel::setThermalGuardEnabled,
                            onToggleSmartGate = viewModel::setSmartGateEnabled,
                            onToggleStartupUpdateCheck = viewModel::setCheckUpdatesOnStartup,
                            onCheckUpdates = { viewModel.checkForUpdates(forceRefresh = true) },
                            onDownloadUpdate = viewModel::downloadAndVerifyUpdate,
                            onInstallUpdate = viewModel::installVerifiedUpdate,
                            onGrantInstallPerm = viewModel::openInstallPermissionSettings,
                            onRerunCalibration = viewModel::runGpuCalibration,
                            onOpenWelcomeGuide = {
                                showSettings = false
                                viewModel.reopenOnboarding()
                            }
                        )
                    }

                    else -> {
                        HomeDashboardScreen(
                            uiState = uiState,
                            onOpenSettings = { showSettings = true },
                            onToggleLanguage = viewModel::toggleLanguage,
                            onSelectMode = viewModel::setInterpolationMode,
                            onToggleSession = {
                                val current = uiState.sessionState
                                if (current is PipelineSessionState.Running || current is PipelineSessionState.Starting) {
                                    viewModel.stopCaptureSession()
                                } else {
                                    mediaProjectionLauncher.launch(
                                        FrameCaptureService.createCaptureConsentIntent(this@MainActivity)
                                    )
                                }
                            },
                            onRunCalibration = viewModel::runGpuCalibration,
                            onBuildReport = viewModel::buildCompatibilityReport,
                            onDownloadUpdate = viewModel::downloadAndVerifyUpdate,
                            onInstallUpdate = viewModel::installVerifiedUpdate,
                            onGrantInstallPerm = viewModel::openInstallPermissionSettings
                        )
                    }
                }
            }
        }
    }
}
