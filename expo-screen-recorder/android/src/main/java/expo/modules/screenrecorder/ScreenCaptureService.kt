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

    // Tracks whether cleanUp() is already running to prevent reentrant calls
    // (e.g. onDestroy fires while projectionCallback.onStop() is mid-execution).
    private var isCleaningUp = false

    // API 34+: the OS can revoke the projection at any time (user dismisses the
    // cast notification, another app steals it, etc.). We register this before
    // createVirtualDisplay() and unregister before mediaProjection.stop().
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Runs on the Handler thread we pass to registerCallback() — main looper.
            cleanUp()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        // Stored as Intent? because the two-branch SDK check blocks Kotlin smart-cast.
        // We null-check explicitly below before passing to startRecording().
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

        // ── Rule 1: startForeground() MUST be called before getMediaProjection().
        // On targetSDK 36 the OS validates the mediaProjection FGS type is active
        // server-side before it will hand out the projection token.
        startForegroundNotification(includeAudio)

        if (resultData == null) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // ── Rule 2: postDelayed yields the main looper so the Binder round-trip for
        // startForeground() completes on ActivityManagerService before we call
        // getMediaProjection(). Thread.sleep() on the main thread would block the
        // looper that needs to receive the Binder reply — a deadlock, not a solution.
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

        // ── Security check: getMediaProjection() throws SecurityException if:
        //   • the FGS mediaProjection type isn't fully registered yet  (race — solved by postDelayed)
        //   • the token was already consumed (one-time use)
        //   • resultCode != RESULT_OK
        // All three are caught and handled without crashing.
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

        // ── Rule 3: register the callback on the main looper BEFORE createVirtualDisplay().
        // On API 34+ this is required — without it the system logs a strict-mode
        // violation and may refuse to honour the projection.
        // We guard with UPSIDE_DOWN_CAKE (API 34) because that's when the requirement
        // was introduced; the call is safe on earlier APIs too but isn't mandatory.
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
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
            // MICROPHONE type is only required/allowed as an additional type from API 34+.
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
        // can both fire in close succession (e.g. user revokes cast permission just
        // as the service is being stopped). Without this gate, mediaRecorder?.stop()
        // could be called twice on an already-released recorder, throwing an
        // IllegalStateException that would crash the app.
        if (isCleaningUp) return
        isCleaningUp = true

        try { if (isRecording) mediaRecorder?.stop() } catch (_: Exception) {}
        isRecording = false

        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        // Unregister BEFORE stop() so the callback can't fire after resources are freed.
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