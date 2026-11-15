package com.framewise.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
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
import com.framewise.app.autopilot.AutoPilotManager
import com.framewise.app.gpu.DisplayResizeState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class FrameBoostOverlayService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null

    private var gameSurfaceView: SurfaceView? = null
    private var gameLayoutParams: WindowManager.LayoutParams? = null

    private var hudContainer: LinearLayout? = null
    private var hudLayoutParams: WindowManager.LayoutParams? = null
    private var hudTitleView: TextView? = null
    private var hudCollapseView: TextView? = null
    private var hudBodyLayout: LinearLayout? = null
    private var hudMetricsView: TextView? = null
    private var hudThermalBadgeView: TextView? = null
    private var hudModeButtons: Map<HudQuickMode, TextView> = emptyMap()
    private var hudExpanded = true

    private var currentPreferredModeId: Int = 0
    private var currentTargetHz: Float = 60f
    private var lastKnownDisplayWidth: Int = 0
    private var lastKnownDisplayHeight: Int = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                onDisplayMetricsPossiblyChanged()
            }
        }
    }

    @Volatile
    var lastForegroundPackage: String? = null
        private set

    @Volatile
    private var lastActiveGamePackage: String? = null

    private val autoCollapseRunnable = Runnable {
        if (hudContainer != null && hudExpanded) {
            setHudExpanded(false)
        }
    }

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
        displayManager = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.also { dm ->
            dm.registerDisplayListener(displayListener, mainHandler)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onDisplayMetricsPossiblyChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!PipelineController.isAutoPilotActive()) return

        val pkg = event.packageName?.toString()
        if (pkg.isNullOrBlank() || AutoPilotManager.isIgnoredPackage(pkg, packageName)) return
        if (pkg == lastActiveGamePackage) return

        lastActiveGamePackage = pkg
        lastForegroundPackage = pkg
        PipelineController.onForegroundPackageDetected(applicationContext, pkg)
    }

    override fun onInterrupt() {
        removeOverlaysInternal()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        removeOverlaysInternal()
        DisplayRefreshController.restoreOriginalRefreshRate(this)
        PipelineController.stopSession(this)
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        removeOverlaysInternal()
        DisplayRefreshController.restoreOriginalRefreshRate(this)
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun attachOverlaysInternal(preferredModeId: Int, targetHz: Float) {
        currentPreferredModeId = preferredModeId
        currentTargetHz = targetHz
        DisplayRefreshController.applyTargetRefreshRate(this, targetHz)
        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }

        if (gameSurfaceView == null) {
            createFullScreenRenderWindow(wm, preferredModeId)
        } else {
            val lp = gameLayoutParams
            if (lp != null) {
                var layoutChanged = false
                if (preferredModeId > 0 && lp.preferredDisplayModeId != preferredModeId) {
                    lp.preferredDisplayModeId = preferredModeId
                    layoutChanged = true
                }
                if (lp.preferredRefreshRate != targetHz) {
                    lp.preferredRefreshRate = targetHz
                    layoutChanged = true
                }
                if (layoutChanged) {
                    runCatching { wm.updateViewLayout(gameSurfaceView, lp) }
                }
            }
            gameSurfaceView?.holder?.surface?.let { surface ->
                if (surface.isValid) {
                    applyAdaptiveRefreshRate(surface, targetHz)
                }
            }
        }

        val existingHud = hudContainer
        val existingHudLp = hudLayoutParams
        if (existingHud == null) {
            createDraggableHudWindow(wm)
        } else if (existingHudLp != null) {
            // Mirror preferred display mode onto the touchable HUD window; Android ignores
            // preferredDisplayModeId on FLAG_NOT_TOUCHABLE overlay windows.
            var hudChanged = false
            if (preferredModeId > 0 && existingHudLp.preferredDisplayModeId != preferredModeId) {
                existingHudLp.preferredDisplayModeId = preferredModeId
                hudChanged = true
            }
            if (existingHudLp.preferredRefreshRate != targetHz) {
                existingHudLp.preferredRefreshRate = targetHz
                hudChanged = true
            }
            if (hudChanged) {
                runCatching { wm.updateViewLayout(existingHud, existingHudLp) }
            }
            updateHudModeHighlight()
        }
    }

    private fun createFullScreenRenderWindow(wm: WindowManager, preferredModeId: Int) {
        val (initialW, initialH, _) = queryCurrentDisplayMetrics(wm)
        lastKnownDisplayWidth = initialW
        lastKnownDisplayHeight = initialH

        val surfaceView = SurfaceView(this).apply {
            alpha = 0f
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    applyAdaptiveRefreshRate(holder.surface, currentTargetHz)
                    val frame = holder.surfaceFrame
                    val (realW, realH, densityDpi) = queryCurrentDisplayMetrics(wm)
                    val w = if (frame.width() > 1) frame.width() else realW
                    val h = if (frame.height() > 1) frame.height() else realH
                    lastKnownDisplayWidth = w
                    lastKnownDisplayHeight = h
                    PipelineController.onOverlaySurfaceAvailable(
                        surface = holder.surface,
                        width = w,
                        height = h,
                        densityDpi = densityDpi
                    )
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    applyAdaptiveRefreshRate(holder.surface, currentTargetHz)
                    val (realW, realH, densityDpi) = queryCurrentDisplayMetrics(wm)
                    val orientationMismatch = (realW > realH) != (width > height)
                    val effectiveW = if (orientationMismatch) realW else width.coerceAtLeast(1)
                    val effectiveH = if (orientationMismatch) realH else height.coerceAtLeast(1)
                    lastKnownDisplayWidth = effectiveW
                    lastKnownDisplayHeight = effectiveH
                    PipelineController.onOverlaySurfaceAvailable(
                        surface = holder.surface,
                        width = effectiveW,
                        height = effectiveH,
                        densityDpi = densityDpi
                    )
                    PipelineController.onDisplaySizeChanged(effectiveW, effectiveH, densityDpi)
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
            this.preferredRefreshRate = currentTargetHz
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

        val bodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val thermalBadgeView = TextView(this).apply {
            setTextColor(Color.parseColor("#FFB300"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, 0)
            visibility = View.GONE
        }

        val metricsText = TextView(this).apply {
            setTextColor(Color.parseColor("#EAF2F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(6))
        }

        val modeButtons = LinkedHashMap<HudQuickMode, TextView>(4)
        val switcherContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val modeRows = listOf(
            listOf(
                HudQuickMode.BOOST_120_PLUS to R.string.hud_mode_120_boost,
                HudQuickMode.SMOOTH_60 to R.string.hud_mode_60_smooth
            ),
            listOf(
                HudQuickMode.FSR_CAS_ONLY to R.string.hud_mode_fsr_cas,
                HudQuickMode.AUTO to R.string.hud_mode_auto
            )
        )
        modeRows.forEachIndexed { rowIndex, pairList ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) {
                    setPadding(0, dp(4), 0, 0)
                }
            }
            pairList.forEachIndexed { colIndex, (quickMode, labelRes) ->
                val btn = TextView(this).apply {
                    text = getString(labelRes)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        PipelineController.selectQuickModeFromHud(
                            context = this@FrameBoostOverlayService,
                            quickMode = quickMode
                        )
                        updateHudModeHighlight()
                        updateHudText()
                        scheduleAutoCollapse()
                    }
                }
                val btnLp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (colIndex == 0) marginEnd = dp(4)
                }
                rowLayout.addView(btn, btnLp)
                modeButtons[quickMode] = btn
            }
            switcherContainer.addView(rowLayout)
        }

        bodyLayout.addView(thermalBadgeView)
        bodyLayout.addView(metricsText)
        bodyLayout.addView(switcherContainer)

        collapseView.setOnClickListener {
            setHudExpanded(!hudExpanded)
        }

        root.addView(headerRow)
        root.addView(bodyLayout)

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
            if (currentPreferredModeId > 0) {
                this.preferredDisplayModeId = currentPreferredModeId
            }
            this.preferredRefreshRate = currentTargetHz
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
                        val (screenW, screenH, _) = queryCurrentDisplayMetrics(wm)
                        val (clampedX, clampedY) = DisplayResizeState.clampHudPositionToSafeBounds(
                            x = startX + dx,
                            y = startY + dy,
                            hudWidth = root.width.takeIf { it > 0 } ?: dp(160),
                            hudHeight = root.height.takeIf { it > 0 } ?: dp(48),
                            screenWidth = screenW,
                            screenHeight = screenH,
                            marginPx = dp(8),
                            minTopPx = dp(24)
                        )
                        params.x = clampedX
                        params.y = clampedY
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        setHudExpanded(!hudExpanded)
                    } else if (hudExpanded) {
                        scheduleAutoCollapse()
                    }
                    true
                }

                else -> false
            }
        }

        wm.addView(root, params)
        hudContainer = root
        hudLayoutParams = params
        hudTitleView = titleView
        hudCollapseView = collapseView
        hudBodyLayout = bodyLayout
        hudMetricsView = metricsText
        hudThermalBadgeView = thermalBadgeView
        hudModeButtons = modeButtons
        hudExpanded = true

        updateHudModeHighlight()
        updateHudText()
        mainHandler.removeCallbacks(hudUpdateRunnable)
        mainHandler.postDelayed(hudUpdateRunnable, 250L)
        scheduleAutoCollapse()
    }

    private fun setHudExpanded(expanded: Boolean) {
        hudExpanded = expanded
        hudBodyLayout?.visibility = if (expanded) View.VISIBLE else View.GONE
        hudCollapseView?.text = if (expanded) "  [−]  " else "  [+]  "
        updateHudText()
        if (expanded) {
            updateHudModeHighlight()
            scheduleAutoCollapse()
        } else {
            mainHandler.removeCallbacks(autoCollapseRunnable)
        }
        val wm = windowManager ?: return
        hudContainer?.post { clampHudWindowBounds(wm) }
    }

    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY_MS)
    }

    private fun clampHudWindowBounds(wm: WindowManager, screenW: Int? = null, screenH: Int? = null) {
        val view = hudContainer ?: return
        val params = hudLayoutParams ?: return
        val (resolvedW, resolvedH) = if (screenW != null && screenH != null) {
            screenW to screenH
        } else {
            val metrics = queryCurrentDisplayMetrics(wm)
            metrics.first to metrics.second
        }
        val (clampedX, clampedY) = DisplayResizeState.clampHudPositionToSafeBounds(
            x = params.x,
            y = params.y,
            hudWidth = view.width.takeIf { it > 0 } ?: dp(160),
            hudHeight = view.height.takeIf { it > 0 } ?: dp(48),
            screenWidth = resolvedW,
            screenHeight = resolvedH,
            marginPx = dp(8),
            minTopPx = dp(24)
        )
        if (clampedX != params.x || clampedY != params.y) {
            params.x = clampedX
            params.y = clampedY
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun updateHudModeHighlight() {
        val activeMode = PipelineController.activeQuickMode()
        hudModeButtons.forEach { (quickMode, button) ->
            val selected = quickMode == activeMode
            button.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (selected) "#2E00E5FF" else "#161E29"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor(if (selected) "#00E5FF" else "#33475B"))
            }
            button.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#9FB0C4"))
        }
    }

    private fun updateHudText() {
        val m = PipelineController.latestMetricsSnapshot()
        hudTitleView?.let { title ->
            title.text = if (hudExpanded) {
                getString(R.string.app_name)
            } else {
                val fpsInt = if (m.outputFps >= 1f) m.outputFps.roundToInt() else currentTargetHz.roundToInt()
                "⚡ $fpsInt FPS"
            }
        }
        if (!hudExpanded) return

        val tv = hudMetricsView ?: return
        hudThermalBadgeView?.let { badge ->
            if (!m.thermalBadge.isNullOrBlank()) {
                badge.text = m.thermalBadge
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }
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
        mainHandler.removeCallbacks(autoCollapseRunnable)
        val wm = windowManager
        hudContainer?.let { view ->
            runCatching { wm?.removeViewImmediate(view) }
        }
        gameSurfaceView?.let { view ->
            runCatching { wm?.removeViewImmediate(view) }
        }
        hudContainer = null
        hudLayoutParams = null
        hudTitleView = null
        hudCollapseView = null
        hudBodyLayout = null
        hudMetricsView = null
        hudThermalBadgeView = null
        hudModeButtons = emptyMap()
        gameSurfaceView = null
        gameLayoutParams = null
        DisplayRefreshController.restoreOriginalRefreshRate(this)
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

    private fun onDisplayMetricsPossiblyChanged() {
        val wm = windowManager ?: return
        val (realW, realH, densityDpi) = queryCurrentDisplayMetrics(wm)
        if (realW == lastKnownDisplayWidth && realH == lastKnownDisplayHeight) return

        lastKnownDisplayWidth = realW
        lastKnownDisplayHeight = realH
        gameSurfaceView?.alpha = 0f

        val view = gameSurfaceView
        val lp = gameLayoutParams
        if (view != null && lp != null) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            runCatching { wm.updateViewLayout(view, lp) }
        }
        clampHudWindowBounds(wm, realW, realH)
        PipelineController.onDisplaySizeChanged(realW, realH, densityDpi)
    }

    private fun queryCurrentDisplayMetrics(wm: WindowManager): Triple<Int, Int, Int> {
        val densityDpi = resources.displayMetrics.densityDpi.coerceAtLeast(120)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return Triple(
                bounds.width().coerceAtLeast(1),
                bounds.height().coerceAtLeast(1),
                densityDpi
            )
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Triple(
            dm.widthPixels.coerceAtLeast(1),
            dm.heightPixels.coerceAtLeast(1),
            dm.densityDpi.coerceAtLeast(120)
        )
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val AUTO_COLLAPSE_DELAY_MS = 6000L

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

        fun setOverlayRenderVisible(visible: Boolean) {
            val service = instance ?: return
            service.mainHandler.post {
                val view = service.gameSurfaceView ?: return@post
                val targetAlpha = if (visible) 1f else 0f
                if (view.alpha != targetAlpha) {
                    view.alpha = targetAlpha
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
