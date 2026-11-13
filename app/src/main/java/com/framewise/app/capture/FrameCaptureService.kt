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
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import com.framewise.app.MainActivity
import com.framewise.app.R

class FrameCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayDensityDpi: Int = DisplayMetrics.DENSITY_DEFAULT

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
            virtualDisplay?.resize(evenW, evenH, displayDensityDpi)
            PipelineController.onCapturedContentResized(evenW, evenH)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardownCapture()
            PipelineController.stopRenderPipelineOnly()
            FrameBoostOverlayService.hideOverlays()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // Strict Android 14-17+ rule: startForeground with MEDIA_PROJECTION type BEFORE getMediaProjection.
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

        mediaProjection = projection
        // Strict Android 14-17+ rule: registerCallback BEFORE createVirtualDisplay.
        projection.registerCallback(projectionCallback, mainHandler)

        val (captureW, captureH, densityDpi) = resolveCaptureMetrics()
        displayDensityDpi = densityDpi

        PipelineController.startRenderPipeline(
            context = this,
            captureWidth = captureW,
            captureHeight = captureH,
            targetHz = targetHz,
            preferredModeId = preferredModeId,
            presetLabel = presetLabel,
            onInputSurfaceReady = { inputSurface ->
                mainHandler.post {
                    val activeProjection = mediaProjection ?: return@post
                    if (virtualDisplay == null) {
                        virtualDisplay = activeProjection.createVirtualDisplay(
                            "FramewiseZeroCopyCapture",
                            captureW,
                            captureH,
                            displayDensityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            inputSurface,
                            null,
                            mainHandler
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

    private fun resolveCaptureMetrics(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val rawW = mode?.physicalWidth ?: dm.widthPixels
        val rawH = mode?.physicalHeight ?: dm.heightPixels
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

        fun createCaptureConsentIntent(context: Context): Intent {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Open-ended API 34+ check covers Android 14, 15, 16, 17, and future releases.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                manager.createScreenCaptureIntent()
            }
        }
    }
}
