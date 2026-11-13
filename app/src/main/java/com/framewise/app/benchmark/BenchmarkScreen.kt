package com.framewise.app.benchmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.framewise.app.BuildConfig
import com.framewise.app.MainUiState
import com.framewise.app.R
import com.framewise.app.device.InterpolationMode
import com.framewise.app.ui.FramewiseCard
import com.framewise.app.ui.SpecRow
import com.framewise.app.ui.theme.FramewiseColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BenchmarkScreen(
    uiState: MainUiState,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settings = uiState.settings
    val profile = uiState.hardwareProfile
    val panelMaxHz = profile?.maxRefreshRateHz ?: 60f

    var selectedMode by remember {
        mutableStateOf(
            if (panelMaxHz >= 88f) InterpolationMode.COMPETITIVE_60_120 else InterpolationMode.CONSOLE_30_60
        )
    }
    var casStrength by remember { mutableFloatStateOf(settings.casSharpness) }
    var motionScale by remember { mutableIntStateOf(settings.motionScalePercent) }
    var snapshot by remember { mutableStateOf(BenchmarkSnapshot()) }

    val renderer = remember {
        BenchmarkRenderer(
            context = context.applicationContext,
            hudProtectedRectsProvider = { uiState.settings.hudZones.toGlUvFloatArray() },
            onSnapshotUpdated = { updated -> snapshot = updated }
        )
    }

    LaunchedEffect(selectedMode, casStrength, motionScale, settings.hudMaskEnabled, panelMaxHz) {
        renderer.mode = selectedMode
        renderer.casSharpness = casStrength
        renderer.motionScalePercent = motionScale
        renderer.hudMaskEnabled = settings.hudMaskEnabled
        renderer.panelMaxHz = panelMaxHz
    }

    var glViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            glViewRef?.queueEvent { renderer.release() }
        }
    }

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
                title = { Text(text = stringResource(R.string.benchmark_title)) }
            )
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(FramewiseColors.ObsidianBg, RoundedCornerShape(14.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        GLSurfaceView(ctx).apply {
                            setEGLContextClientVersion(3)
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    applySurfaceRate(holder.surface, panelMaxHz)
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    applySurfaceRate(holder.surface, panelMaxHz)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                            })
                            glViewRef = this
                        }
                    }
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = FramewiseColors.ObsidianBg.copy(alpha = 0.82f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.benchmark_top_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = FramewiseColors.AmberWarning,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = FramewiseColors.ObsidianBg.copy(alpha = 0.82f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.benchmark_bottom_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = FramewiseColors.ElectricEmerald,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            FramewiseCard(borderColor = FramewiseColors.ElectricEmerald.copy(alpha = 0.55f)) {
                Text(
                    text = stringResource(R.string.benchmark_live_metrics),
                    style = MaterialTheme.typography.titleMedium,
                    color = FramewiseColors.ElectricEmerald
                )
                SpecRow(
                    stringResource(R.string.benchmark_base_fps),
                    String.format(Locale.US, "%.1f FPS", snapshot.baseRateHz)
                )
                SpecRow(
                    stringResource(R.string.benchmark_output_fps),
                    String.format(Locale.US, "%.1f FPS", snapshot.outputFps)
                )
                SpecRow(
                    stringResource(R.string.telemetry_frame_time),
                    String.format(Locale.US, "%.2f ms", snapshot.frameTimeMs)
                )
                SpecRow(
                    stringResource(R.string.telemetry_gpu_pass),
                    String.format(Locale.US, "%.2f ms", snapshot.gpuShaderTimeMs)
                )
            }

            FramewiseCard {
                Text(
                    text = stringResource(R.string.benchmark_controls),
                    style = MaterialTheme.typography.titleMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedMode == InterpolationMode.CONSOLE_30_60,
                        onClick = { selectedMode = InterpolationMode.CONSOLE_30_60 },
                        label = { Text(stringResource(R.string.mode_console_30_60)) }
                    )
                    FilterChip(
                        selected = selectedMode == InterpolationMode.COMPETITIVE_60_120,
                        onClick = { selectedMode = InterpolationMode.COMPETITIVE_60_120 },
                        label = { Text(stringResource(R.string.mode_competitive_60_120)) }
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 40, 60).forEach { scale ->
                        FilterChip(
                            selected = motionScale == scale,
                            onClick = { motionScale = scale },
                            label = { Text("$scale%") }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_cas_strength),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f", casStrength),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = casStrength,
                    onValueChange = { casStrength = it },
                    valueRange = 0f..1f
                )

                val deviceModel = profile?.deviceModel ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val md = snapshot.toMarkdownReport(
                                appName = context.getString(R.string.app_name),
                                versionName = BuildConfig.VERSION_NAME,
                                deviceModel = deviceModel
                            )
                            copyText(context, "Framewise Benchmark Markdown", md)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Text(stringResource(R.string.btn_export_markdown))
                    }
                    OutlinedButton(
                        onClick = {
                            val json = snapshot.toJsonReport(
                                deviceModel = deviceModel,
                                versionName = BuildConfig.VERSION_NAME
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, "Framewise Benchmark JSON")
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(shareIntent, context.getString(R.string.btn_export_json))
                                )
                            }.onFailure {
                                copyText(context, "Framewise Benchmark JSON", json)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Text(stringResource(R.string.btn_export_json))
                    }
                }
            }
        }
    }
}

private fun applySurfaceRate(surface: Surface, panelMaxHz: Float) {
    if (!surface.isValid) return
    val targetHz = panelMaxHz.coerceAtLeast(60f)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            surface.setFrameRate(
                targetHz,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS
            )
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            surface.setFrameRate(targetHz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(R.string.benchmark_copied_msg), Toast.LENGTH_SHORT).show()
}
