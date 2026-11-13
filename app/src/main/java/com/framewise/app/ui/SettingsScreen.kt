package com.framewise.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framewise.app.BuildConfig
import com.framewise.app.CalibrationUiState
import com.framewise.app.MainUiState
import com.framewise.app.R
import com.framewise.app.UpdaterUiState
import com.framewise.app.ui.theme.FramewiseColors
import com.framewise.app.updater.ReleaseInfo
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onNavigateBack: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectMotionScale: (Int) -> Unit,
    onChangeCasSharpness: (Float) -> Unit,
    onSelectTargetFps: (Int) -> Unit,
    onToggleAutoPilot: (Boolean) -> Unit,
    onToggleHudMask: (Boolean) -> Unit,
    onToggleThermalGuard: (Boolean) -> Unit,
    onToggleStartupUpdateCheck: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: (ReleaseInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
    onGrantInstallPerm: () -> Unit,
    onRerunCalibration: () -> Unit,
    onOpenWelcomeGuide: () -> Unit
) {
    val settings = uiState.settings
    val profile = uiState.hardwareProfile
    val allowedFps = profile?.allowedTargetFpsOptions() ?: listOf(60)
    val maxPanelHz = (profile?.maxRefreshRateHz ?: 60f).toInt()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                title = { Text(text = stringResource(R.string.settings_title)) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Language & RTL/LTR
            FramewiseCard {
                Text(
                    text = stringResource(R.string.settings_section_language),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !settings.languageTag.startsWith("ar"),
                        onClick = { onSelectLanguage("en") },
                        label = { Text(stringResource(R.string.lang_english)) }
                    )
                    FilterChip(
                        selected = settings.languageTag.startsWith("ar"),
                        onClick = { onSelectLanguage("ar") },
                        label = { Text(stringResource(R.string.lang_arabic)) }
                    )
                }
            }

            // GPU Interpolation & CAS
            FramewiseCard {
                Text(
                    text = stringResource(R.string.settings_section_gpu),
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = stringResource(R.string.settings_motion_scale),
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        25 to R.string.scale_25,
                        40 to R.string.scale_40,
                        60 to R.string.scale_60
                    ).forEach { (scale, labelRes) ->
                        FilterChip(
                            selected = settings.motionScalePercent == scale,
                            onClick = { onSelectMotionScale(scale) },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_cas_strength),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f", settings.casSharpness),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = settings.casSharpness,
                    onValueChange = onChangeCasSharpness,
                    valueRange = 0f..1f
                )

                Text(
                    text = stringResource(R.string.settings_target_fps),
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allowedFps.forEach { fps ->
                        FilterChip(
                            selected = settings.targetFps == fps,
                            onClick = { onSelectTargetFps(fps) },
                            label = { Text("$fps FPS") }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_fps_capped_note, maxPanelHz),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Auto-Pilot & Protections
            FramewiseCard {
                Text(
                    text = stringResource(R.string.settings_section_automation),
                    style = MaterialTheme.typography.titleMedium
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_autopilot),
                    checked = settings.autoPilotEnabled,
                    onCheckedChange = onToggleAutoPilot
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_hud_mask),
                    checked = settings.hudMaskEnabled,
                    onCheckedChange = onToggleHudMask
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_thermal_guard),
                    checked = settings.thermalGuardEnabled,
                    onCheckedChange = onToggleThermalGuard
                )
            }

            // Secure GitHub Release Updater
            FramewiseCard {
                Text(
                    text = stringResource(R.string.settings_section_updater),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.settings_current_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_check_startup),
                    checked = settings.checkUpdatesOnStartup,
                    onCheckedChange = onToggleStartupUpdateCheck
                )

                when (val upd = uiState.updaterState) {
                    UpdaterUiState.Idle -> {
                        Text(
                            text = stringResource(R.string.updater_idle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    UpdaterUiState.Checking -> {
                        Text(
                            text = stringResource(R.string.updater_checking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    is UpdaterUiState.UpToDate -> {
                        Text(
                            text = stringResource(R.string.updater_up_to_date, upd.currentVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = FramewiseColors.ElectricEmerald
                        )
                    }

                    is UpdaterUiState.Available -> {
                        Text(
                            text = stringResource(R.string.updater_available, upd.release.version.toString()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FramewiseColors.ElectricEmerald
                        )
                        if (upd.release.releaseNotes.isNotBlank()) {
                            Text(
                                text = upd.release.releaseNotes.take(400),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { onDownloadUpdate(upd.release) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.btn_download_install, upd.release.version.toString()))
                        }
                    }

                    is UpdaterUiState.Downloading -> {
                        Text(
                            text = stringResource(R.string.updater_downloading, upd.progressPercent),
                            style = MaterialTheme.typography.bodySmall
                        )
                        LinearProgressIndicator(
                            progress = { upd.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is UpdaterUiState.Verified -> {
                        Text(
                            text = stringResource(R.string.updater_verified),
                            style = MaterialTheme.typography.bodySmall,
                            color = FramewiseColors.ElectricEmerald
                        )
                        Button(
                            onClick = { onInstallUpdate(upd.apkFile) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.btn_install_now))
                        }
                    }

                    UpdaterUiState.NeedsInstallPermission -> {
                        OutlinedButton(
                            onClick = onGrantInstallPerm,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.btn_grant_install_perm))
                        }
                    }

                    UpdaterUiState.RateLimited -> {
                        Text(
                            text = stringResource(R.string.updater_rate_limited),
                            style = MaterialTheme.typography.bodySmall,
                            color = FramewiseColors.AmberWarning
                        )
                    }

                    is UpdaterUiState.Error -> {
                        Text(
                            text = stringResource(R.string.updater_error, upd.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                OutlinedButton(
                    onClick = onCheckUpdates,
                    enabled = uiState.updaterState !is UpdaterUiState.Checking &&
                        uiState.updaterState !is UpdaterUiState.Downloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_check_updates))
                }
            }

            // Calibration & Onboarding Tools
            FramewiseCard {
                Text(
                    text = stringResource(R.string.settings_section_tools),
                    style = MaterialTheme.typography.titleMedium
                )
                if (uiState.calibrationState is CalibrationUiState.Running) {
                    LinearProgressIndicator(
                        progress = { uiState.calibrationState.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(
                    onClick = onRerunCalibration,
                    enabled = uiState.calibrationState !is CalibrationUiState.Running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_rerun_calibration))
                }
                OutlinedButton(
                    onClick = onOpenWelcomeGuide,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_reopen_onboarding))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
