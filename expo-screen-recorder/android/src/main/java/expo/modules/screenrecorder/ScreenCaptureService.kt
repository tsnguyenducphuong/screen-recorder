package expo.modules.screenrecorder

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    // Required on API 34+: projection can be revoked externally (e.g. user dismisses
    // the notification). We must listen and tear down gracefully.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Called on the main thread when the system revokes the projection.
            cleanUp()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        // Capture as a local val with explicit non-null check below.
        // The two-branch SDK check prevents Kotlin smart-cast from narrowing to Intent,
        // so we store it as Intent? and guard explicitly.
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("RESULT_DATA")
        }

        val outputPath   = intent?.getStringExtra("OUTPUT_PATH") ?: ""
        val width        = intent?.getIntExtra("WIDTH", 720)     ?: 720
        val height       = intent?.getIntExtra("HEIGHT", 1280)   ?: 1280
        val dpi          = intent?.getIntExtra("DPI", 1)         ?: 1
        val includeAudio = intent?.getBooleanExtra("INCLUDE_AUDIO", false) ?: false
        val quality      = intent?.getStringExtra("QUALITY")     ?: "high"

        // Step 1: Call startForeground() FIRST so the OS registers the mediaProjection
        // foreground service type before getMediaProjection() is called.
        startForegroundNotification(includeAudio)

        // Fix: explicit non-null check after startForeground so stopSelf() is safe to call.
        if (resultData == null) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // Step 2: postDelayed yields the main thread looper so the Binder IPC for
        // startForeground() can fully complete on the system side first.
        // On targetSDK 36, calling getMediaProjection() synchronously after startForeground()
        // on the same thread races against the OS registering the service type, causing
        // a SecurityException. Thread.sleep() would deadlock the looper instead.
        Handler(mainLooper).postDelayed({
            startRecording(resultCode, resultData, outputPath, width, height, dpi, includeAudio, quality)
        }, 300L)

        return START_NOT_STICKY
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,       // non-null: caller already checked
        outputPath: String,
        width: Int,
        height: Int,
        dpi: Int,
        includeAudio: Boolean,
        quality: String
    ) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Acquire the one-time MediaProjection token.
        try {
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
        } catch (e: SecurityException) {
            e.printStackTrace()
            stopForegroundCompat()
            stopSelf()
            return
        } catch (e: Exception) {
            e.printStackTrace()
            stopForegroundCompat()
            stopSelf()
            return
        }

        if (mediaProjection == null) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        // Fix: register the projection callback BEFORE createVirtualDisplay().
        // Required on API 34+; safe to call on all versions via the compat branch.
        // Without this, the OS may silently revoke the projection on API 34+ and
        // we'd have no way to clean up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(projectionCallback, Handler(mainLooper))
        }

        try {
            setupMediaRecorder(outputPath, width, height, includeAudio, quality)
        } catch (e: Exception) {
            e.printStackTrace()
            cleanUp()
            return
        }

        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ExpoScreenCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null,
                null
            )
            if (virtualDisplay == null) {
                throw IllegalStateException("createVirtualDisplay returned null")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            cleanUp()
            return
        }

        try {
            mediaRecorder?.start()
            isRecording = true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanUp()
        }
    }

    private fun setupMediaRecorder(
        path: String,
        width: Int,
        height: Int,
        includeAudio: Boolean,
        quality: String
    ) {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            if (includeAudio) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(File(path).absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (includeAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            setVideoSize(width, height)
            setVideoEncodingBitRate(
                when (quality) {
                    "low"    -> 2 * 1024 * 1024
                    "medium" -> 4 * 1024 * 1024
                    else     -> 8 * 1024 * 1024
                }
            )
            setVideoFrameRate(30)
            prepare()
        }
    }

    private fun startForegroundNotification(includeAudio: Boolean) {
        val channelId = "screen_record_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Recording Active")
            .setContentText("Capturing screen...")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

            if (includeAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(101, notification, serviceType)
        } else {
            startForeground(101, notification)
        }
    }

    // Dismiss the persistent notification before stopping, compatible across API levels.
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun cleanUp() {
        try { if (isRecording) mediaRecorder?.stop() } catch (_: Exception) {}
        isRecording = false

        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        // Unregister the callback before stopping the projection to avoid a
        // callback firing after resources are already released.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.unregisterCallback(projectionCallback)
            }
        } catch (_: Exception) {}

        mediaProjection?.stop()
        mediaProjection = null

        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }
}