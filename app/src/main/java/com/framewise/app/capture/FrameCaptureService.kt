package com.framewise.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.framewise.app.MainActivity
import com.framewise.app.R
import com.framewise.app.gpu.DisplayResizeState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FrameCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val captureThreadTid = AtomicInteger(-1)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayDensityDpi: Int = DisplayMetrics.DENSITY_DEFAULT
    private var currentRawWidth: Int = 0
    private var currentRawHeight: Int = 0
    private var currentCaptureWidth: Int = 0
    private var currentCaptureHeight: Int = 0

    override fun onCreate() {
        super.onCreate()
        val readyLatch = CountDownLatch(1)
        val thread = object : HandlerThread("FramewiseCaptureThread") {
            override fun onLooperPrepared() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                captureThreadTid.set(Process.myTid())
                readyLatch.countDown()
            }
        }
        thread.start()
        readyLatch.await(150L, TimeUnit.MILLISECONDS)
        captureThread = thread
        captureHandler = Handler(thread.looper)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            teardownCapture()
            PipelineController.stopSession(this@FrameCaptureService)
            stopSelfSafely()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val evenW = (width and 1.inv()).coerceAtLeast(16)
            val evenH = (height and 1.inv()).coerceAtLeast(16)
            // Ignore virtual-display self-echoes at scaled 75% resolution to avoid double downscaling.
            if (evenW == currentCaptureWidth && evenH == currentCaptureHeight) return
            resizeCaptureTarget(evenW, evenH, displayDensityDpi)
            PipelineController.onCapturedContentResized(evenW, evenH)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        if (intent?.action == ACTION_STOP) {
            teardownCapture()
            PipelineController.stopRenderPipelineOnly()
            DisplayRefreshController.restoreOriginalRefreshRate(this)
            FrameBoostOverlayService.hideOverlays()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // Android 14+ requires startForeground with MEDIA_PROJECTION type before getMediaProjection.
        promoteToForeground()

        val token = MediaProjectionTokenStore.consume()
        if (token == null) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val targetHz = intent?.getFloatExtra(EXTRA_TARGET_HZ, 60f) ?: 60f
        val preferredModeId = intent?.getIntExtra(EXTRA_PREFERRED_MODE_ID, 0) ?: 0
        val presetLabel = intent?.getStringExtra(EXTRA_PRESET_LABEL) ?: "1x Passthrough"

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(token.resultCode, token.data)
        } catch (t: Throwable) {
            PipelineController.stopSession(this, t.message ?: "MediaProjection denied")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (projection == null) {
            PipelineController.stopSession(this, "MediaProjection returned null")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val activeCaptureHandler = captureHandler ?: mainHandler
        mediaProjection = projection
        // Android 14+ requires registering callback before createVirtualDisplay.
        projection.registerCallback(projectionCallback, activeCaptureHandler)

        val (rawW, rawH, densityDpi) = resolveCaptureMetrics()
        val motionScale = PipelineController.currentMotionScalePercent()
        val spec = DisplayResizeState.computeResizeSpec(rawW, rawH, densityDpi, motionScale)
        currentRawWidth = spec.surfaceWidth
        currentRawHeight = spec.surfaceHeight
        currentCaptureWidth = spec.captureWidth
        currentCaptureHeight = spec.captureHeight
        displayDensityDpi = spec.densityDpi

        PipelineController.startRenderPipeline(
            context = this,
            captureWidth = spec.surfaceWidth,
            captureHeight = spec.surfaceHeight,
            targetHz = targetHz,
            preferredModeId = preferredModeId,
            presetLabel = presetLabel,
            captureThreadTid = captureThreadTid.get().takeIf { it > 0 } ?: Process.myTid(),
            onInputSurfaceReady = { inputSurface ->
                activeCaptureHandler.post {
                    val activeProjection = mediaProjection ?: return@post
                    if (virtualDisplay == null) {
                        virtualDisplay = activeProjection.createVirtualDisplay(
                            "FramewiseZeroCopyCapture",
                            currentCaptureWidth,
                            currentCaptureHeight,
                            displayDensityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            inputSurface,
                            null,
                            activeCaptureHandler
                        )
                    }
                }
            },
            onError = { errorMsg ->
                mainHandler.post {
                    teardownCapture()
                    PipelineController.stopSession(this@FrameCaptureService, errorMsg)
                    stopSelfSafely()
                }
            }
        )

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardownCapture()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun promoteToForeground() {
        ensureNotificationChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FrameCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.hud_stop), stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun resizeCaptureTarget(newRawWidth: Int, newRawHeight: Int, densityDpi: Int) {
        val safeRawW = newRawWidth.coerceAtLeast(16)
        val safeRawH = newRawHeight.coerceAtLeast(16)
        val safeDpi = densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
        val spec = DisplayResizeState.computeResizeSpec(
            rawWidth = safeRawW,
            rawHeight = safeRawH,
            densityDpi = safeDpi,
            motionScalePercent = PipelineController.currentMotionScalePercent()
        )
        currentRawWidth = spec.surfaceWidth
        currentRawHeight = spec.surfaceHeight
        if (spec.captureWidth == currentCaptureWidth &&
            spec.captureHeight == currentCaptureHeight &&
            spec.densityDpi == displayDensityDpi
        ) {
            return
        }
        currentCaptureWidth = spec.captureWidth
        currentCaptureHeight = spec.captureHeight
        displayDensityDpi = spec.densityDpi
        virtualDisplay?.resize(currentCaptureWidth, currentCaptureHeight, displayDensityDpi)
    }

    private fun refreshCaptureSpatialScale() {
        if (currentRawWidth <= 0 || currentRawHeight <= 0) return
        resizeCaptureTarget(currentRawWidth, currentRawHeight, displayDensityDpi)
    }

    private fun resolveCaptureMetrics(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rawW: Int
        val rawH: Int
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            rawW = bounds.width()
            rawH = bounds.height()
        } else if (wm != null) {
            val realMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(realMetrics)
            rawW = realMetrics.widthPixels
            rawH = realMetrics.heightPixels
        } else {
            rawW = dm.widthPixels
            rawH = dm.heightPixels
        }
        val evenW = (rawW and 1.inv()).coerceAtLeast(16)
        val evenH = (rawH and 1.inv()).coerceAtLeast(16)
        return Triple(evenW, evenH, dm.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM))
    }

    private fun teardownCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
        }
        mediaProjection = null
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.framewise.app.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.framewise.app.ACTION_STOP_CAPTURE"
        const val EXTRA_TARGET_HZ = "extra_target_hz"
        const val EXTRA_PREFERRED_MODE_ID = "extra_preferred_mode_id"
        const val EXTRA_PRESET_LABEL = "extra_preset_label"

        private const val CHANNEL_ID = "framewise_capture_channel"
        private const val NOTIFICATION_ID = 4102

        @Volatile
        private var instance: FrameCaptureService? = null

        fun onDisplaySizeChanged(newWidth: Int, newHeight: Int, densityDpi: Int) {
            val service = instance ?: return
            service.mainHandler.post {
                service.resizeCaptureTarget(newWidth, newHeight, densityDpi)
            }
        }

        fun onSpatialCaptureScaleChanged() {
            val service = instance ?: return
            service.mainHandler.post {
                service.refreshCaptureSpatialScale()
            }
        }

        fun createCaptureConsentIntent(context: Context): Intent {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                manager.createScreenCaptureIntent()
            }
        }
    }
}
