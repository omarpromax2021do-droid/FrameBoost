package com.framewise.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framewise.app.R
import com.framewise.app.autopilot.HudProtectionZonesConfig
import com.framewise.app.autopilot.NormalizedRect
import com.framewise.app.ui.theme.FramewiseColors
import kotlin.math.roundToInt

@Composable
fun HudZoneEditorDialog(
    initialConfig: HudProtectionZonesConfig,
    onDismiss: () -> Unit,
    onSave: (HudProtectionZonesConfig) -> Unit
) {
    var crosshairEnabled by remember { mutableStateOf(initialConfig.crosshairEnabled) }
    var crosshairHalfSize by remember {
        mutableFloatStateOf(((initialConfig.crosshair.right - initialConfig.crosshair.left) * 0.5f).coerceIn(0.03f, 0.15f))
    }
    var minimapEnabled by remember { mutableStateOf(initialConfig.minimapEnabled) }
    var minimapWidth by remember { mutableFloatStateOf(initialConfig.minimap.right.coerceIn(0.12f, 0.35f)) }
    var minimapHeight by remember { mutableFloatStateOf(initialConfig.minimap.bottom.coerceIn(0.15f, 0.42f)) }
    var leftControlsEnabled by remember { mutableStateOf(initialConfig.leftControlsEnabled) }
    var rightControlsEnabled by remember { mutableStateOf(initialConfig.rightControlsEnabled) }
    var controlsHeight by remember {
        mutableFloatStateOf((1f - initialConfig.leftControls.top).coerceIn(0.20f, 0.50f))
    }

    val previewConfig = HudProtectionZonesConfig(
        crosshairEnabled = crosshairEnabled,
        crosshair = NormalizedRect(
            left = 0.5f - crosshairHalfSize,
            top = 0.5f - crosshairHalfSize,
            right = 0.5f + crosshairHalfSize,
            bottom = 0.5f + crosshairHalfSize
        ).normalized(),
        minimapEnabled = minimapEnabled,
        minimap = NormalizedRect(0f, 0f, minimapWidth, minimapHeight).normalized(),
        leftControlsEnabled = leftControlsEnabled,
        leftControls = NormalizedRect(0f, 1f - controlsHeight, 0.30f, 1f).normalized(),
        rightControlsEnabled = rightControlsEnabled,
        rightControls = NormalizedRect(0.70f, 1f - controlsHeight, 1f, 1f).normalized()
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.hud_editor_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(FramewiseColors.ObsidianBg, RoundedCornerShape(10.dp))
                ) {
                    fun drawRectZone(enabled: Boolean, rect: NormalizedRect, color: Color) {
                        if (!enabled) return
                        drawRect(
                            color = color.copy(alpha = 0.28f),
                            topLeft = Offset(rect.left * size.width, rect.top * size.height),
                            size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height)
                        )
                        drawRect(
                            color = color,
                            topLeft = Offset(rect.left * size.width, rect.top * size.height),
                            size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    drawRectZone(previewConfig.crosshairEnabled, previewConfig.crosshair, FramewiseColors.NeonCyan)
                    drawRectZone(previewConfig.minimapEnabled, previewConfig.minimap, FramewiseColors.ElectricEmerald)
                    drawRectZone(previewConfig.leftControlsEnabled, previewConfig.leftControls, FramewiseColors.AmberWarning)
                    drawRectZone(previewConfig.rightControlsEnabled, previewConfig.rightControls, FramewiseColors.AmberWarning)
                }

                ZoneSwitchRow(stringResource(R.string.hud_zone_crosshair), crosshairEnabled) {
                    crosshairEnabled = it
                }
                if (crosshairEnabled) {
                    Text(
                        text = "${stringResource(R.string.hud_zone_crosshair_size)}: ${(crosshairHalfSize * 200).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = crosshairHalfSize,
                        onValueChange = { crosshairHalfSize = it },
                        valueRange = 0.03f..0.15f
                    )
                }

                ZoneSwitchRow(stringResource(R.string.hud_zone_minimap), minimapEnabled) {
                    minimapEnabled = it
                }
                if (minimapEnabled) {
                    Text(
                        text = "${stringResource(R.string.hud_zone_minimap_size)}: ${(minimapWidth * 100).roundToInt()}% × ${(minimapHeight * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = minimapWidth,
                        onValueChange = { minimapWidth = it },
                        valueRange = 0.12f..0.35f
                    )
                    Slider(
                        value = minimapHeight,
                        onValueChange = { minimapHeight = it },
                        valueRange = 0.15f..0.42f
                    )
                }

                ZoneSwitchRow(stringResource(R.string.hud_zone_left_controls), leftControlsEnabled) {
                    leftControlsEnabled = it
                }
                ZoneSwitchRow(stringResource(R.string.hud_zone_right_controls), rightControlsEnabled) {
                    rightControlsEnabled = it
                }
                if (leftControlsEnabled || rightControlsEnabled) {
                    Text(
                        text = "${stringResource(R.string.hud_zone_controls_height)}: ${(controlsHeight * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = controlsHeight,
                        onValueChange = { controlsHeight = it },
                        valueRange = 0.20f..0.50f
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(previewConfig) }) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        val def = HudProtectionZonesConfig()
                        crosshairEnabled = def.crosshairEnabled
                        crosshairHalfSize = (def.crosshair.right - def.crosshair.left) * 0.5f
                        minimapEnabled = def.minimapEnabled
                        minimapWidth = def.minimap.right
                        minimapHeight = def.minimap.bottom
                        leftControlsEnabled = def.leftControlsEnabled
                        rightControlsEnabled = def.rightControlsEnabled
                        controlsHeight = 1f - def.leftControls.top
                    }
                ) {
                    Text(stringResource(R.string.btn_reset_defaults))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    )
}

@Composable
private fun ZoneSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
