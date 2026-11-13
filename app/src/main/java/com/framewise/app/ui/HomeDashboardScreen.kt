package com.framewise.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.framewise.app.CalibrationUiState
import com.framewise.app.MainUiState
import com.framewise.app.R
import com.framewise.app.UpdaterUiState
import com.framewise.app.autopilot.GameProfileRepository
import com.framewise.app.capture.FrameBoostOverlayService
import com.framewise.app.capture.PipelineSessionState
import com.framewise.app.device.InterpolationMode
import com.framewise.app.ui.theme.FramewiseColors
import com.framewise.app.updater.ReleaseInfo
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeDashboardScreen(
    uiState: MainUiState,
    onOpenSettings: () -> Unit,
    onOpenBenchmark: () -> Unit,
    onOpenHudEditor: () -> Unit,
    onOpenAppsPicker: () -> Unit,
    onBoostMemory: (String?) -> Unit,
    onRemoveCustomGame: (String) -> Unit,
    onToggleLanguage: () -> Unit,
    onSelectMode: (InterpolationMode) -> Unit,
    onToggleSession: () -> Unit,
    onRunCalibration: () -> Unit,
    onBuildReport: () -> String?,
    onDownloadUpdate: (ReleaseInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
    onGrantInstallPerm: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val profile = uiState.hardwareProfile
    val settings = uiState.settings
    val allowedFps = profile?.allowedTargetFpsOptions() ?: listOf(60)
    val supportsHighRefresh = allowedFps.any { it >= 90 }

    var accessibilityEnabled by remember {
        mutableStateOf(FrameBoostOverlayService.isServiceEnabled(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = FrameBoostOverlayService.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isSessionActive = uiState.sessionState is PipelineSessionState.Running ||
        uiState.sessionState is PipelineSessionState.Starting

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    LanguageToggleChip(onToggle = onToggleLanguage)
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.nav_settings),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
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
            // Update banner if available/downloading/verified
            DashboardUpdateBanner(
                updaterState = uiState.updaterState,
                onDownload = onDownloadUpdate,
                onInstall = onInstallUpdate,
                onGrantInstallPerm = onGrantInstallPerm
            )

            // Honest technical pipeline expectations banner
            FramewiseCard(borderColor = FramewiseColors.AmberWarning.copy(alpha = 0.5f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = FramewiseColors.AmberWarning
                    )
                    Text(
                        text = stringResource(R.string.dashboard_honest_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Quick Start & Active Mode Card
            FramewiseCard(borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.quick_start_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (profile != null) {
                        HardwareTierBadge(tier = profile.hardwareTier)
                    }
                }

                Text(
                    text = stringResource(R.string.active_profile_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModeFilterChip(
                        selected = settings.interpolationMode == InterpolationMode.CONSOLE_30_60,
                        enabled = true,
                        label = stringResource(R.string.mode_console_30_60),
                        onClick = { onSelectMode(InterpolationMode.CONSOLE_30_60) }
                    )
                    ModeFilterChip(
                        selected = settings.interpolationMode == InterpolationMode.COMPETITIVE_60_120,
                        enabled = supportsHighRefresh,
                        label = stringResource(R.string.mode_competitive_60_120),
                        onClick = { onSelectMode(InterpolationMode.COMPETITIVE_60_120) }
                    )
                    ModeFilterChip(
                        selected = settings.interpolationMode == InterpolationMode.STABILIZER_60_CAS,
                        enabled = true,
                        label = stringResource(R.string.mode_stabilizer_60_cas),
                        onClick = { onSelectMode(InterpolationMode.STABILIZER_60_CAS) }
                    )
                }

                Text(
                    text = "${interpolationModeTitle(settings.interpolationMode)} • ${settings.motionScalePercent}% • CAS ${
                        String.format(Locale.US, "%.2f", settings.casSharpness)
                    } • ${settings.targetFps} FPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = stringResource(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            R.string.capture_phase4_note
                        } else {
                            R.string.capture_pre_u_note
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.sessionState is PipelineSessionState.Error) {
                    Text(
                        text = uiState.sessionState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (uiState.calibrationState is CalibrationUiState.Running) {
                    LinearProgressIndicator(
                        progress = { uiState.calibrationState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = FramewiseColors.ElectricEmerald
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (isSessionActive) {
                                onToggleSession()
                            } else if (!accessibilityEnabled) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.accessibility_required_msg),
                                    Toast.LENGTH_LONG
                                ).show()
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            } else {
                                onToggleSession()
                            }
                        },
                        colors = if (isSessionActive) {
                            ButtonDefaults.buttonColors(
                                containerColor = FramewiseColors.CoralError,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = when {
                                isSessionActive -> Icons.Rounded.Stop
                                !accessibilityEnabled -> Icons.Rounded.AccessibilityNew
                                else -> Icons.Rounded.PlayArrow
                            },
                            contentDescription = null
                        )
                        Text(
                            text = stringResource(
                                when {
                                    isSessionActive -> R.string.btn_stop_frameboost
                                    !accessibilityEnabled -> R.string.btn_enable_accessibility
                                    else -> R.string.btn_start_frameboost
                                }
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = onRunCalibration,
                        enabled = uiState.calibrationState !is CalibrationUiState.Running && !isSessionActive
                    ) {
                        Text(text = stringResource(R.string.btn_calibrate_now))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenBenchmark,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Compare, contentDescription = null)
                        Text(text = stringResource(R.string.btn_open_benchmark))
                    }
                    OutlinedButton(
                        onClick = onOpenHudEditor,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = null)
                        Text(text = stringResource(R.string.btn_edit_hud_zones))
                    }
                }
            }

            // Pre-Launch RAM Booster & Thermal Guard Card
            FramewiseCard {
                Text(
                    text = stringResource(R.string.ram_thermal_title),
                    style = MaterialTheme.typography.titleMedium
                )
                SpecRow(stringResource(R.string.gamemode_label), uiState.gameModeLabel)

                val thermal = uiState.thermalState
                val thermalText = if (thermal.isThrottled) {
                    stringResource(
                        R.string.thermal_throttled_format,
                        thermal.hudWarningBadge ?: thermal.severity.name,
                        thermal.batteryTempCelsius
                    )
                } else {
                    stringResource(
                        R.string.thermal_normal_format,
                        thermal.batteryTempCelsius,
                        thermal.severity.name
                    )
                }
                SpecRow(stringResource(R.string.thermal_status_label), thermalText)

                uiState.lastBoostReport?.let { report ->
                    SpecRow(
                        stringResource(R.string.last_boost_label),
                        stringResource(
                            R.string.last_boost_value,
                            report.freedRamMb,
                            report.killedPackagesCount,
                            report.availableRamMbAfter
                        )
                    )
                }

                OutlinedButton(
                    onClick = { onBoostMemory(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                    Text(text = stringResource(R.string.btn_boost_ram))
                }
            }

            if (uiState.sessionState is PipelineSessionState.Running) {
                val m = uiState.sessionState.metrics
                FramewiseCard(borderColor = FramewiseColors.ElectricEmerald.copy(alpha = 0.65f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = FramewiseColors.ElectricEmerald
                        )
                        Text(
                            text = stringResource(R.string.live_telemetry_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = FramewiseColors.ElectricEmerald
                        )
                    }
                    SpecRow(
                        stringResource(R.string.telemetry_base_to_output),
                        String.format(Locale.US, "%.1f -> %.1f FPS", m.captureFps, m.outputFps)
                    )
                    SpecRow(
                        stringResource(R.string.telemetry_frame_time),
                        String.format(Locale.US, "%.2f ms", m.frameTimeMs)
                    )
                    SpecRow(
                        stringResource(R.string.telemetry_gpu_pass),
                        String.format(Locale.US, "%.2f ms", m.gpuPassTimeMs)
                    )
                    SpecRow(
                        stringResource(R.string.telemetry_latency),
                        String.format(Locale.US, "%.2f ms", m.pipelineLatencyMs)
                    )
                    SpecRow(
                        stringResource(R.string.telemetry_cas_scale),
                        "CAS ${m.casSharpnessPercent}% • ${m.effectiveMotionScalePercent}%"
                    )
                    SpecRow(
                        stringResource(R.string.telemetry_smart_gate),
                        m.smartGateStatus
                    )
                }
            }

            // Auto-Pilot Status Card
            FramewiseCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = FramewiseColors.ElectricEmerald
                        )
                        Text(
                            text = stringResource(R.string.autopilot_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (settings.autoPilotEnabled) {
                            FramewiseColors.ElectricEmerald.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = stringResource(
                                if (settings.autoPilotEnabled) R.string.status_enabled else R.string.status_disabled
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (settings.autoPilotEnabled) {
                                FramewiseColors.ElectricEmerald
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.autopilot_codm),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.autopilot_codm_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = FramewiseColors.BorderSubtle)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.autopilot_rdr1),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.autopilot_rdr1_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                uiState.activeAutoPilotPreset?.let { preset ->
                    Text(
                        text = stringResource(R.string.autopilot_active_profile, preset.displayName, preset.presetLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = FramewiseColors.ElectricEmerald
                    )
                }

                val installedBuiltIns = remember {
                    val pm = context.packageManager
                    GameProfileRepository.builtInProfiles.values.filter {
                        pm.getLaunchIntentForPackage(it.packageName) != null
                    }
                }

                if (installedBuiltIns.isNotEmpty() || settings.customProfiles.isNotEmpty()) {
                    HorizontalDivider(color = FramewiseColors.BorderSubtle)
                    Text(
                        text = stringResource(R.string.autopilot_custom_games_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    installedBuiltIns.forEach { game ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = game.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onBoostMemory(game.packageName) }) {
                                Text(stringResource(R.string.btn_boost_launch))
                            }
                        }
                    }
                    settings.customProfiles.forEach { custom ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = custom.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${custom.preferredMotionScale}% • CAS ${
                                        String.format(Locale.US, "%.2f", custom.preferredCasSharpness)
                                    }",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onBoostMemory(custom.packageName) }) {
                                Text(stringResource(R.string.btn_boost_launch))
                            }
                            TextButton(onClick = { onRemoveCustomGame(custom.packageName) }) {
                                Text(stringResource(R.string.btn_remove), color = FramewiseColors.CoralError)
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onOpenAppsPicker,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.btn_add_custom_game))
                }
            }

            // Hardware & Display Profiler Card
            FramewiseCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.hardware_card_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (profile != null) {
                    SpecRow(stringResource(R.string.hw_soc), profile.socModel)
                    SpecRow(stringResource(R.string.hw_gpu), "${profile.glVendor} • ${profile.glRenderer}")
                    SpecRow(stringResource(R.string.hw_gles), profile.glesVersion)
                    SpecRow(
                        stringResource(R.string.hw_ram),
                        "${profile.availableRamMb} MB / ${profile.totalRamMb} MB"
                    )
                    val modesText = profile.supportedDisplayModes
                        .map { "${it.width}x${it.height}@${String.format(Locale.US, "%.0f", it.refreshRateHz)}Hz" }
                        .distinct()
                        .joinToString(", ")
                    SpecRow(stringResource(R.string.hw_display_modes), modesText.ifBlank { "60Hz" })
                    val calText = profile.lastCalibrationMs?.let {
                        String.format(Locale.US, "%.2f ms", it)
                    } ?: stringResource(R.string.hw_not_calibrated)
                    SpecRow(stringResource(R.string.hw_calibrated_pass), calText)

                    OutlinedButton(
                        onClick = {
                            val report = onBuildReport()
                            if (report != null) {
                                copyTextToClipboard(context, "Framewise Compatibility Report", report)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.report_copied_msg),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Rounded.ContentCopy, contentDescription = null)
                        Text(text = stringResource(R.string.btn_copy_report))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModeFilterChip(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text = label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun DashboardUpdateBanner(
    updaterState: UpdaterUiState,
    onDownload: (ReleaseInfo) -> Unit,
    onInstall: (File) -> Unit,
    onGrantInstallPerm: () -> Unit
) {
    when (updaterState) {
        is UpdaterUiState.Available -> {
            FramewiseCard(borderColor = FramewiseColors.ElectricEmerald.copy(alpha = 0.7f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = FramewiseColors.ElectricEmerald
                    )
                    Text(
                        text = stringResource(R.string.updater_available, updaterState.release.version.toString()),
                        style = MaterialTheme.typography.titleMedium,
                        color = FramewiseColors.ElectricEmerald
                    )
                }
                Button(
                    onClick = { onDownload(updaterState.release) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_download_install, updaterState.release.version.toString()))
                }
            }
        }

        is UpdaterUiState.Downloading -> {
            FramewiseCard(borderColor = MaterialTheme.colorScheme.primary) {
                Text(
                    text = stringResource(R.string.updater_downloading, updaterState.progressPercent),
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = { updaterState.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        is UpdaterUiState.Verified -> {
            FramewiseCard(borderColor = FramewiseColors.ElectricEmerald) {
                Text(
                    text = stringResource(R.string.updater_verified),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FramewiseColors.ElectricEmerald
                )
                Button(
                    onClick = { onInstall(updaterState.apkFile) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_install_now))
                }
            }
        }

        UpdaterUiState.NeedsInstallPermission -> {
            FramewiseCard(borderColor = FramewiseColors.AmberWarning) {
                OutlinedButton(
                    onClick = onGrantInstallPerm,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, FramewiseColors.AmberWarning)
                ) {
                    Text(stringResource(R.string.btn_grant_install_perm))
                }
            }
        }

        else -> Unit
    }
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
