package com.framewise.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framewise.app.R
import com.framewise.app.autopilot.GameProfile
import com.framewise.app.autopilot.GameProfileCategory
import com.framewise.app.autopilot.GameProfileRepository
import com.framewise.app.device.InterpolationMode
import com.framewise.app.ui.theme.FramewiseColors
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstalledAppsPickerDialog(
    supportsHighRefresh: Boolean,
    onDismiss: () -> Unit,
    onSaveProfile: (GameProfile) -> Unit
) {
    val context = LocalContext.current
    val apps = remember { GameProfileRepository.listInstalledLaunchableApps(context) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var chosenMode by remember {
        mutableStateOf(
            if (supportsHighRefresh) InterpolationMode.COMPETITIVE_60_120 else InterpolationMode.CONSOLE_30_60
        )
    }
    var chosenScale by remember { mutableIntStateOf(40) }
    var chosenCas by remember { mutableFloatStateOf(0.65f) }
    var chosenHudMask by remember { mutableStateOf(true) }

    val filtered = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter {
                it.appLabel.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (selectedPkg == null) R.string.picker_title else R.string.picker_configure_title
                )
            )
        },
        text = {
            if (selectedPkg == null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.picker_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPkg = app.packageName
                                        selectedName = app.appLabel
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = app.appLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HorizontalDivider(color = FramewiseColors.BorderSubtle)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "$selectedName\n$selectedPkg",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = chosenMode == InterpolationMode.CONSOLE_30_60,
                            onClick = { chosenMode = InterpolationMode.CONSOLE_30_60 },
                            label = { Text(stringResource(R.string.mode_console_30_60)) }
                        )
                        FilterChip(
                            selected = chosenMode == InterpolationMode.COMPETITIVE_60_120,
                            enabled = supportsHighRefresh,
                            onClick = { chosenMode = InterpolationMode.COMPETITIVE_60_120 },
                            label = { Text(stringResource(R.string.mode_competitive_60_120)) }
                        )
                        FilterChip(
                            selected = chosenMode == InterpolationMode.STABILIZER_60_CAS,
                            onClick = { chosenMode = InterpolationMode.STABILIZER_60_CAS },
                            label = { Text(stringResource(R.string.mode_stabilizer_60_cas)) }
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(25, 40, 60).forEach { sc ->
                            FilterChip(
                                selected = chosenScale == sc,
                                onClick = { chosenScale = sc },
                                label = { Text("$sc%") }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_cas_strength), style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "%.2f", chosenCas), style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = chosenCas,
                        onValueChange = { chosenCas = it },
                        valueRange = 0f..1f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings_hud_mask), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = chosenHudMask, onCheckedChange = { chosenHudMask = it })
                    }
                }
            }
        },
        confirmButton = {
            if (selectedPkg != null) {
                Button(
                    onClick = {
                        onSaveProfile(
                            GameProfile(
                                packageName = selectedPkg!!,
                                displayName = selectedName,
                                category = GameProfileCategory.CUSTOM,
                                preferredMode = chosenMode,
                                preferredMotionScale = chosenScale,
                                preferredCasSharpness = chosenCas,
                                hudMaskEnabled = chosenHudMask
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selectedPkg != null) {
                    TextButton(onClick = { selectedPkg = null }) {
                        Text(stringResource(R.string.btn_back))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    )
}
