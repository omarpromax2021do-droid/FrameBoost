package com.framewise.app.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.framewise.app.CalibrationUiState
import com.framewise.app.MainUiState
import com.framewise.app.R
import com.framewise.app.ui.FramewiseCard
import com.framewise.app.ui.HardwareTierBadge
import com.framewise.app.ui.LanguageToggleChip
import com.framewise.app.ui.interpolationModeTitle
import com.framewise.app.ui.theme.FramewiseColors
import java.util.Locale

@Composable
fun WelcomeOnboardingScreen(
    uiState: MainUiState,
    onToggleLanguage: () -> Unit,
    onRunCalibration: () -> Unit,
    onFinishOnboarding: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    val totalSteps = 4

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.onboarding_step_format, currentStep, totalSteps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LanguageToggleChip(onToggle = onToggleLanguage)
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 1) {
                        OutlinedButton(onClick = { currentStep-- }) {
                            Text(stringResource(R.string.btn_back))
                        }
                    } else {
                        Spacer(modifier = Modifier.height(1.dp))
                    }

                    if (currentStep < totalSteps) {
                        Button(onClick = { currentStep++ }) {
                            Text(stringResource(R.string.btn_next))
                        }
                    } else {
                        val isCalibrated = uiState.calibrationState is CalibrationUiState.Completed ||
                            uiState.settings.lastCalibrationMs != null
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isCalibrated && uiState.calibrationState !is CalibrationUiState.Running) {
                                TextButton(onClick = onFinishOnboarding) {
                                    Text(stringResource(R.string.btn_skip_calibration))
                                }
                            }
                            Button(
                                onClick = onFinishOnboarding,
                                enabled = uiState.calibrationState !is CalibrationUiState.Running
                            ) {
                                Text(stringResource(R.string.btn_finish))
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LinearProgressIndicator(
                progress = { currentStep / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            when (currentStep) {
                1 -> StepOverviewContent()
                2 -> StepHonestExpectationsContent()
                3 -> StepPermissionsContent()
                4 -> StepCalibrationContent(
                    uiState = uiState,
                    onRunCalibration = onRunCalibration
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StepOverviewContent() {
    Text(
        text = stringResource(R.string.onboarding_s1_title),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        text = stringResource(R.string.onboarding_s1_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FeatureExplainCard(
        icon = Icons.Rounded.Speed,
        title = stringResource(R.string.onboarding_s1_card1_title),
        body = stringResource(R.string.onboarding_s1_card1_desc)
    )
    FeatureExplainCard(
        icon = Icons.Rounded.Bolt,
        title = stringResource(R.string.onboarding_s1_card2_title),
        body = stringResource(R.string.onboarding_s1_card2_desc)
    )
}

@Composable
private fun FeatureExplainCard(icon: ImageVector, title: String, body: String) {
    FramewiseCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StepHonestExpectationsContent() {
    Text(
        text = stringResource(R.string.onboarding_s2_title),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        text = stringResource(R.string.onboarding_s2_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FramewiseCard(borderColor = FramewiseColors.AmberWarning.copy(alpha = 0.55f)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = FramewiseColors.AmberWarning
            )
            Text(
                text = stringResource(R.string.onboarding_s2_title),
                style = MaterialTheme.typography.titleMedium,
                color = FramewiseColors.AmberWarning
            )
        }
        Text(text = stringResource(R.string.onboarding_s2_point1), style = MaterialTheme.typography.bodyMedium)
        Text(text = stringResource(R.string.onboarding_s2_point2), style = MaterialTheme.typography.bodyMedium)
        Text(text = stringResource(R.string.onboarding_s2_point3), style = MaterialTheme.typography.bodyMedium)
        Text(text = stringResource(R.string.onboarding_s2_point4), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StepPermissionsContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var writeSettingsGranted by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var accessibilityEnabled by remember {
        mutableStateOf(com.framewise.app.capture.FrameBoostOverlayService.isServiceEnabled(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = hasNotificationPermission(context)
                batteryUnrestricted = isIgnoringBatteryOptimizations(context)
                writeSettingsGranted = Settings.System.canWrite(context)
                accessibilityEnabled =
                    com.framewise.app.capture.FrameBoostOverlayService.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted || hasNotificationPermission(context)
    }

    Text(
        text = stringResource(R.string.onboarding_s3_title),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        text = stringResource(R.string.onboarding_s3_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    PermissionSetupCard(
        icon = Icons.AutoMirrored.Rounded.ScreenShare,
        title = stringResource(R.string.perm_capture_title),
        description = stringResource(R.string.perm_capture_desc),
        isReady = false,
        actionLabel = stringResource(R.string.perm_capture_badge),
        onAction = null
    )

    PermissionSetupCard(
        icon = Icons.Rounded.AccessibilityNew,
        title = stringResource(R.string.perm_accessibility_title),
        description = stringResource(R.string.perm_accessibility_desc),
        isReady = accessibilityEnabled,
        actionLabel = stringResource(if (accessibilityEnabled) R.string.btn_granted else R.string.btn_grant),
        onAction = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    )

    PermissionSetupCard(
        icon = Icons.Rounded.NotificationsActive,
        title = stringResource(R.string.perm_notifications_title),
        description = stringResource(R.string.perm_notifications_desc),
        isReady = notificationsGranted,
        actionLabel = stringResource(if (notificationsGranted) R.string.btn_granted else R.string.btn_grant),
        onAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )

    PermissionSetupCard(
        icon = Icons.Rounded.BatteryChargingFull,
        title = stringResource(R.string.perm_battery_title),
        description = stringResource(R.string.perm_battery_desc),
        isReady = batteryUnrestricted,
        actionLabel = stringResource(if (batteryUnrestricted) R.string.btn_granted else R.string.btn_grant),
        onAction = {
            if (!batteryUnrestricted) {
                val requestIntent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                runCatching { context.startActivity(requestIntent) }.onFailure {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        }
    )

    PermissionSetupCard(
        icon = Icons.Rounded.Speed,
        title = stringResource(R.string.perm_write_settings_title),
        description = stringResource(R.string.perm_write_settings_desc),
        isReady = writeSettingsGranted,
        actionLabel = stringResource(if (writeSettingsGranted) R.string.btn_granted else R.string.btn_grant),
        onAction = {
            if (!writeSettingsGranted) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
        }
    )
}

@Composable
private fun PermissionSetupCard(
    icon: ImageVector,
    title: String,
    description: String,
    isReady: Boolean,
    actionLabel: String,
    onAction: (() -> Unit)?
) {
    FramewiseCard(
        borderColor = if (isReady) FramewiseColors.ElectricEmerald.copy(alpha = 0.6f) else FramewiseColors.BorderSubtle
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isReady) Icons.Rounded.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (isReady) FramewiseColors.ElectricEmerald else MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    enabled = !isReady,
                    border = BorderStroke(
                        1.dp,
                        if (isReady) FramewiseColors.ElectricEmerald else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = actionLabel)
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCalibrationContent(
    uiState: MainUiState,
    onRunCalibration: () -> Unit
) {
    val profile = uiState.hardwareProfile
    Text(
        text = stringResource(R.string.onboarding_s4_title),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        text = stringResource(R.string.onboarding_s4_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    FramewiseCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.deviceModel ?: "Android Device",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${profile?.socModel ?: "..."} • ${profile?.glRenderer ?: "..."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (profile != null) {
                HardwareTierBadge(tier = profile.hardwareTier)
            }
        }

        when (val cal = uiState.calibrationState) {
            CalibrationUiState.Idle -> {
                Button(
                    onClick = onRunCalibration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.btn_run_calibration))
                }
            }

            is CalibrationUiState.Running -> {
                Text(
                    text = stringResource(R.string.calibration_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                LinearProgressIndicator(
                    progress = { cal.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = FramewiseColors.ElectricEmerald
                )
            }

            is CalibrationUiState.Completed -> {
                Text(
                    text = stringResource(R.string.calibration_complete),
                    style = MaterialTheme.typography.titleMedium,
                    color = FramewiseColors.ElectricEmerald,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        stringResource(R.string.calibration_result_format),
                        cal.result.averagePassMs,
                        cal.result.estimatedMaxFps
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        stringResource(R.string.calibration_preset_format),
                        interpolationModeTitle(cal.result.recommendedMode),
                        cal.result.recommendedMotionScalePercent,
                        cal.result.recommendedCasSharpness,
                        cal.result.recommendedTargetFps
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedButton(
                    onClick = onRunCalibration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_rerun_calibration))
                }
            }

            is CalibrationUiState.Failed -> {
                Text(
                    text = cal.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = onRunCalibration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_run_calibration))
                }
            }
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
