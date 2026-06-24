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
    private var isCleaningUp = false

    // API 34+: OS can revoke projection at any time. We must tear down gracefully.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            cleanUp()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        // Typed as Intent? because the two-branch SDK check blocks Kotlin smart-cast.
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("RESULT_DATA")
        }

        val outputPath   = intent?.getStringExtra("OUTPUT_PATH") ?: ""
        val width        = intent?.getIntExtra("WIDTH", 1080)    ?: 1080
        val height       = intent?.getIntExtra("HEIGHT", 1920)   ?: 1920
        val dpi          = intent?.getIntExtra("DPI", 420)       ?: 420
        val includeAudio = intent?.getBooleanExtra("INCLUDE_AUDIO", false) ?: false
        val quality      = intent?.getStringExtra("QUALITY")     ?: "high"

        // BUG FIX 5: Validate that outputPath is non-empty before proceeding.
        // If the module sends an empty path (e.g. cacheDir was null), MediaRecorder
        // will throw a RuntimeException at prepare() with a misleading error message.
        // Catching it here gives a clear failure with no crash.
        if (outputPath.isBlank()) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground() MUST be called before getMediaProjection().
        // On targetSDK 36 the OS validates the mediaProjection FGS type at this
        // call using the grant token embedded in the intent by startForegroundService().
        startForegroundNotification(includeAudio)

        if (resultData == null) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // postDelayed yields the main looper so the Binder IPC for startForeground()
        // fully completes on ActivityManagerService before getMediaProjection() is called.
        // Thread.sleep() would deadlock the same looper — never use it here.
        Handler(mainLooper).postDelayed({
            startRecording(resultCode, resultData, outputPath, width, height, dpi, includeAudio, quality)
        }, 300L)

        return START_NOT_STICKY
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,
        outputPath: String,
        width: Int,
        height: Int,
        dpi: Int,
        includeAudio: Boolean,
        quality: String
    ) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // SecurityException thrown when:
        //  • FGS mediaProjection type not yet registered (race) — solved by postDelayed
        //  • Token already consumed (one-time use)
        //  • resultCode != RESULT_OK
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

        // Required on API 34+: register callback BEFORE createVirtualDisplay().
        // Also handles external revocation (user dismisses cast notification).
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
                "ExpoScreenCapture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )
            if (virtualDisplay == null) throw IllegalStateException("createVirtualDisplay returned null")
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
            if (includeAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(File(path).absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (includeAudio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(width, height)
            setVideoEncodingBitRate(when (quality) {
                "low"    -> 2 * 1024 * 1024
                "medium" -> 4 * 1024 * 1024
                else     -> 8 * 1024 * 1024
            })
            setVideoFrameRate(30)
            prepare()
        }
    }

    private fun startForegroundNotification(includeAudio: Boolean) {
        val channelId = "screen_record_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "Screen Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Recording Active")
            .setContentText("Capturing screen...")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // MICROPHONE type required as additional type from API 34+ when audio is on.
            if (includeAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(101, notification, serviceType)
        } else {
            startForeground(101, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun cleanUp() {
        // Guard against reentrant calls: projectionCallback.onStop() and onDestroy()
        // can fire in close succession. Double-releasing MediaRecorder throws
        // IllegalStateException and crashes the process.
        if (isCleaningUp) return
        isCleaningUp = true

        try { if (isRecording) mediaRecorder?.stop() } catch (_: Exception) {}
        isRecording = false

        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        // Unregister BEFORE stop() so the callback cannot fire on freed resources.
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