package com.framewise.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import com.framewise.app.R
import java.util.Locale
import kotlin.math.abs

class FrameBoostOverlayService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    private var gameSurfaceView: SurfaceView? = null
    private var gameLayoutParams: WindowManager.LayoutParams? = null

    private var hudContainer: LinearLayout? = null
    private var hudLayoutParams: WindowManager.LayoutParams? = null
    private var hudMetricsView: TextView? = null
    private var hudExpanded = true

    private var currentPreferredModeId: Int = 0
    private var currentTargetHz: Float = 60f

    @Volatile
    var lastForegroundPackage: String? = null
        private set

    private val hudUpdateRunnable = object : Runnable {
        override fun run() {
            updateHudText()
            if (hudContainer != null) {
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                lastForegroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        removeOverlaysInternal()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeOverlaysInternal()
        PipelineController.stopSession(this)
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlaysInternal()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun attachOverlaysInternal(preferredModeId: Int, targetHz: Float) {
        currentPreferredModeId = preferredModeId
        currentTargetHz = targetHz
        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }

        if (gameSurfaceView == null) {
            createFullScreenRenderWindow(wm, preferredModeId)
        } else {
            val lp = gameLayoutParams
            if (lp != null && preferredModeId > 0 && lp.preferredDisplayModeId != preferredModeId) {
                lp.preferredDisplayModeId = preferredModeId
                runCatching { wm.updateViewLayout(gameSurfaceView, lp) }
            }
            gameSurfaceView?.holder?.surface?.let { surface ->
                if (surface.isValid) {
                    applyAdaptiveRefreshRate(surface, targetHz)
                }
            }
        }

        if (hudContainer == null) {
            createDraggableHudWindow(wm)
        }
    }

    private fun createFullScreenRenderWindow(wm: WindowManager, preferredModeId: Int) {
        val surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    applyAdaptiveRefreshRate(holder.surface, currentTargetHz)
                    val frame = holder.surfaceFrame
                    PipelineController.onOverlaySurfaceAvailable(
                        surface = holder.surface,
                        width = frame.width().coerceAtLeast(1),
                        height = frame.height().coerceAtLeast(1)
                    )
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    applyAdaptiveRefreshRate(holder.surface, currentTargetHz)
                    PipelineController.onOverlaySurfaceAvailable(
                        surface = holder.surface,
                        width = width.coerceAtLeast(1),
                        height = height.coerceAtLeast(1)
                    )
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    PipelineController.onOverlaySurfaceDestroyed()
                }
            })
        }

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (preferredModeId > 0) {
                this.preferredDisplayModeId = preferredModeId
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        wm.addView(surfaceView, params)
        gameSurfaceView = surfaceView
        gameLayoutParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDraggableHudWindow(wm: WindowManager) {
        val padH = dp(12)
        val padV = dp(8)

        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#DC0A0E14"))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#9900E5FF"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(padH, padV, padH, padV)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val collapseView = TextView(this).apply {
            text = "  [−]  "
            setTextColor(Color.parseColor("#9FB0C4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }

        val stopView = TextView(this).apply {
            text = getString(R.string.hud_stop)
            setTextColor(Color.parseColor("#FF5252"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(2), dp(2))
            setOnClickListener {
                PipelineController.stopSession(this@FrameBoostOverlayService)
            }
        }

        headerRow.addView(titleView)
        headerRow.addView(collapseView)
        headerRow.addView(stopView)

        val metricsText = TextView(this).apply {
            setTextColor(Color.parseColor("#EAF2F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        }

        collapseView.setOnClickListener {
            hudExpanded = !hudExpanded
            metricsText.visibility = if (hudExpanded) View.VISIBLE else View.GONE
            collapseView.text = if (hudExpanded) "  [−]  " else "  [+]  "
        }

        root.addView(headerRow)
        root.addView(metricsText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(48)
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        collapseView.performClick()
                    }
                    true
                }

                else -> false
            }
        }

        wm.addView(root, params)
        hudContainer = root
        hudLayoutParams = params
        hudMetricsView = metricsText

        updateHudText()
        mainHandler.removeCallbacks(hudUpdateRunnable)
        mainHandler.postDelayed(hudUpdateRunnable, 250L)
    }

    private fun updateHudText() {
        val tv = hudMetricsView ?: return
        val m = PipelineController.latestMetricsSnapshot()
        tv.text = String.format(
            Locale.US,
            "%s\n%.1f -> %.1f FPS | FT %.1fms\nGPU %.2fms | LAT %.1fms\nCAS %d%% | Scale %d%%\n%s",
            m.activePreset,
            m.captureFps,
            m.outputFps,
            m.frameTimeMs,
            m.gpuPassTimeMs,
            m.pipelineLatencyMs,
            m.casSharpnessPercent,
            m.effectiveMotionScalePercent,
            m.smartGateStatus
        )
    }

    private fun removeOverlaysInternal() {
        mainHandler.removeCallbacks(hudUpdateRunnable)
        val wm = windowManager
        hudContainer?.let { view ->
            runCatching { wm?.removeViewImmediate(view) }
        }
        gameSurfaceView?.let { view ->
            runCatching { wm?.removeViewImmediate(view) }
        }
        hudContainer = null
        hudLayoutParams = null
        hudMetricsView = null
        gameSurfaceView = null
        gameLayoutParams = null
    }

    private fun applyAdaptiveRefreshRate(surface: Surface, targetHz: Float) {
        if (!surface.isValid) return
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

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        @Volatile
        private var instance: FrameBoostOverlayService? = null

        fun showOverlays(preferredModeId: Int, targetHz: Float) {
            val service = instance ?: return
            service.mainHandler.post {
                service.attachOverlaysInternal(preferredModeId, targetHz)
            }
        }

        fun updateDisplayRate(preferredModeId: Int, targetHz: Float) {
            val service = instance ?: return
            service.mainHandler.post {
                if (service.gameSurfaceView != null) {
                    service.attachOverlaysInternal(preferredModeId, targetHz)
                }
            }
        }

        fun hideOverlays() {
            val service = instance ?: return
            service.mainHandler.post {
                service.removeOverlaysInternal()
            }
        }

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .orEmpty()
            val expectedComponent = ComponentName(context, FrameBoostOverlayService::class.java)
            if (enabledList.any {
                    it.resolveInfo?.serviceInfo?.let { si ->
                        si.packageName == expectedComponent.packageName && si.name == expectedComponent.className
                    } == true
                }
            ) {
                return true
            }
            val enabledSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val flattened = expectedComponent.flattenToString()
            val shortName = expectedComponent.flattenToShortString()
            return enabledSetting.split(':').any {
                it.equals(flattened, ignoreCase = true) || it.equals(shortName, ignoreCase = true)
            }
        }
    }
}
