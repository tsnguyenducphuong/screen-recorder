package expo.modules.screenrecorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecorderException(
    code: String,
    message: String
) : CodedException("[$code] $message")

class ExpoScreenRecorderModule : Module() {

    private var currentOutputFile: File? = null
    private var startTime: Long = 0L
    private val isRecording = AtomicBoolean(false)

    private var pendingPromise: Promise? = null
    private var pendingOptions: RecordingOptions? = null

    private val MEDIA_PROJECTION_REQUEST_CODE = 1001

    override fun definition() = ModuleDefinition {
        Name("ExpoScreenRecorder")

        OnActivityResult { activity, event ->
            if (event.requestCode != MEDIA_PROJECTION_REQUEST_CODE) return@OnActivityResult

            val promise = pendingPromise
            val options = pendingOptions

            // Always clear pending state first — prevents stale callbacks on rotation/re-launch.
            pendingPromise = null
            pendingOptions = null

            if (promise == null) return@OnActivityResult

            if (event.resultCode != Activity.RESULT_OK || event.data == null) {
                isRecording.set(false)
                currentOutputFile = null
                promise.reject("ERR_PERMISSION_DENIED", "User denied screen capture permission.", null)
                return@OnActivityResult
            }

            val resultCode = event.resultCode
            val resultData = event.data!!
            val context = appContext.reactContext

            if (context == null) {
                isRecording.set(false)
                currentOutputFile = null
                promise.reject("ERR_NO_CONTEXT", "React context is unavailable.", null)
                return@OnActivityResult
            }

            val outputFile = currentOutputFile
            if (outputFile == null) {
                isRecording.set(false)
                promise.reject("ERR_NO_FILE", "Output file was not prepared.", null)
                return@OnActivityResult
            }

            // BUG FIX 1: DisplayMetrics fallback to safe defaults when display is null.
            // activity.display is null when the activity is in the background or on
            // certain foldable/multi-display devices. Without a fallback, widthPixels
            // and heightPixels stay 0, which causes MediaRecorder.setVideoSize(0,0)
            // to throw an IllegalArgumentException and crash the service setup.
            val metrics = DisplayMetrics()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                activity.display?.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(metrics)
            }
            val width  = if (metrics.widthPixels  > 0) metrics.widthPixels  else 1080
            val height = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
            val dpi    = if (metrics.densityDpi   > 0) metrics.densityDpi   else 420

            try {
                val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    putExtra("RESULT_CODE",   resultCode)
                    putExtra("RESULT_DATA",   resultData)
                    putExtra("OUTPUT_PATH",   outputFile.absolutePath)
                    putExtra("WIDTH",         width)
                    putExtra("HEIGHT",        height)
                    putExtra("DPI",           dpi)
                    putExtra("INCLUDE_AUDIO", options?.includeAudio ?: false)
                    putExtra("QUALITY",       options?.quality ?: "high")
                }

                // Called on the main thread immediately after consent — satisfies:
                // 1. The while-in-use foreground requirement.
                // 2. The 5-second startForeground() contract.
                // 3. The android:project_media token validation on targetSDK 36.
                context.startForegroundService(serviceIntent)

                startTime = System.currentTimeMillis()
                promise.resolve(null)

            } catch (e: Exception) {
                isRecording.set(false)
                currentOutputFile = null
                promise.reject("ERR_SERVICE_START", "Failed to start recording service: ${e.message}", e)
            }
        }

        AsyncFunction("isAvailableAsync") { promise: Promise ->
            promise.resolve(true)
        }

        AsyncFunction("startRecordingAsync") { options: RecordingOptions, promise: Promise ->
            val context = appContext.reactContext ?: return@AsyncFunction promise.reject(
                "ERR_NO_CONTEXT", "React context is unavailable.", null
            )
            val activity = appContext.currentActivity ?: return@AsyncFunction promise.reject(
                "ERR_NO_ACTIVITY", "Current activity is missing.", null
            )

            if (!isRecording.compareAndSet(false, true)) {
                return@AsyncFunction promise.reject(
                    "ERR_ALREADY_RECORDING", "A recording is already in progress.", null
                )
            }

            // BUG FIX 2: wrap createScreenCaptureIntent() in try/catch.
            // On some OEM ROMs this throws an ActivityNotFoundException or
            // RemoteException if the MediaProjection service is unavailable.
            // Without this catch the whole JS thread crashes silently.
            try {
                currentOutputFile = File(context.cacheDir, "REC_${UUID.randomUUID()}.mp4")
                pendingPromise = promise
                pendingOptions = options

                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

                activity.startActivityForResult(
                    mpManager.createScreenCaptureIntent(),
                    MEDIA_PROJECTION_REQUEST_CODE
                )
            } catch (e: Exception) {
                // Roll back all state so the caller can retry.
                isRecording.set(false)
                currentOutputFile = null
                pendingPromise = null
                pendingOptions = null
                promise.reject("ERR_LAUNCH_CONSENT", "Failed to launch screen capture consent: ${e.message}", e)
            }

            // Promise is resolved/rejected inside OnActivityResult.
        }

        AsyncFunction("stopRecordingAsync") { promise: Promise ->
            val context = appContext.reactContext ?: return@AsyncFunction promise.reject(
                "ERR_NO_CONTEXT", "React context is unavailable.", null
            )

            if (!isRecording.compareAndSet(true, false)) {
                return@AsyncFunction promise.reject(
                    "ERR_NOT_RECORDING", "No recording is currently in progress.", null
                )
            }

            // BUG FIX 3: clear pendingPromise/pendingOptions if stopRecording is called
            // while the consent dialog is still open (user calls stop before approving).
            // Without this, OnActivityResult would fire later and try to start the
            // service after stop was already requested — restarting a cancelled recording.
            pendingPromise?.reject("ERR_STOPPED", "Recording was stopped before consent was given.", null)
            pendingPromise = null
            pendingOptions = null

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withContext(Dispatchers.Main) {
                        context.stopService(Intent(context, ScreenCaptureService::class.java))
                    }

                    // Wait for the service's onDestroy + MediaRecorder.stop() to flush
                    // the MP4 moov atom to disk before we stat the file.
                    delay(300L)

                    val file = currentOutputFile

                    // BUG FIX 4: always clear currentOutputFile AFTER reading it.
                    // If we cleared it before, a rapid start→stop→start sequence could
                    // race and the second start would overwrite currentOutputFile while
                    // the first stop is still reading it. Read first, then clear.
                    currentOutputFile = null

                    if (file == null || !file.exists()) {
                        promise.reject("ERR_NO_FILE", "Output file is missing or the recording failed.", null)
                        return@launch
                    }

                    val durationSec = (System.currentTimeMillis() - startTime) / 1000.0
                    promise.resolve(
                        mapOf(
                            "uri"      to "file://${file.absolutePath}",
                            "duration" to durationSec
                        )
                    )

                } catch (e: Exception) {
                    // Restore flag so the caller can attempt stop again.
                    isRecording.set(true)
                    promise.reject("ERR_STOP_RECORDING", e.message, e)
                }
            }
        }
    }
}