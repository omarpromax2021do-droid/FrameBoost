package com.framewise.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framewise.app.R
import com.framewise.app.device.HardwareTier
import com.framewise.app.device.InterpolationMode
import com.framewise.app.ui.theme.FramewiseColors

@Composable
fun FramewiseCard(
    modifier: Modifier = Modifier,
    borderColor: Color = FramewiseColors.BorderSubtle,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun LanguageToggleChip(
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onToggle,
        modifier = modifier,
        label = { Text(text = stringResource(R.string.lang_switch_label)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.primary,
            leadingIconContentColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
fun HardwareTierBadge(tier: HardwareTier, modifier: Modifier = Modifier) {
    val (labelRes, tint) = when (tier) {
        HardwareTier.HIGH_FLAGSHIP -> R.string.tier_high_flagship to FramewiseColors.ElectricEmerald
        HardwareTier.MID_RANGE_120HZ -> R.string.tier_mid_120hz to FramewiseColors.NeonCyan
        HardwareTier.LOW_LEGACY_60HZ -> R.string.tier_low_60hz to FramewiseColors.AmberWarning
        HardwareTier.UNSUPPORTED -> R.string.tier_unsupported to FramewiseColors.CoralError
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.6f))
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
fun interpolationModeTitle(mode: InterpolationMode): String {
    return when (mode) {
        InterpolationMode.CONSOLE_30_60 -> stringResource(R.string.mode_console_30_60)
        InterpolationMode.COMPETITIVE_60_120 -> stringResource(R.string.mode_competitive_60_120)
        InterpolationMode.STABILIZER_60_CAS -> stringResource(R.string.mode_stabilizer_60_cas)
    }
}
