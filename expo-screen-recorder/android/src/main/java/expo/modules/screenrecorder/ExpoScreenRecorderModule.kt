package expo.modules.screenrecorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import expo.modules.kotlin.activityresult.AppContextActivityResultContract
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------
// Custom AppContextActivityResultContract for the screen-capture permission
// ---------------------------------------------------------------------------

/**
 * Input carries everything needed to build the launch Intent.
 * Output wraps the system's resultCode + data so the coroutine can act on them.
 */
data class ScreenCaptureInput(val captureIntent: Intent)

data class ScreenCaptureResult(val resultCode: Int, val data: Intent?)

class ScreenCaptureContract : AppContextActivityResultContract<ScreenCaptureInput, ScreenCaptureResult> {
    override fun createIntent(input: ScreenCaptureInput): Intent = input.captureIntent

    override fun parseResult(resultCode: Int, intent: Intent?): ScreenCaptureResult =
        ScreenCaptureResult(resultCode, intent)
}

// ---------------------------------------------------------------------------
// Module
// ---------------------------------------------------------------------------

class ExpoScreenRecorderModule : Module() {

    // FIX 1: Launcher is declared with lateinit and registered inside the
    //         RegisterActivityContracts DSL block (not via a property delegate).
    private lateinit var captureLauncher:
            expo.modules.kotlin.activityresult.ActivityResultLauncher<ScreenCaptureInput>

    private var currentOutputFile: File? = null
    private var startTime: Long = 0L
    private var isRecording: Boolean = false

    override fun definition() = ModuleDefinition {
        Name("ExpoScreenRecorder")

        // FIX 2: RegisterActivityContracts is a DSL component inside definition(),
        //         not a property delegate. registerForActivityResult receives
        //         (contract, callback) — the callback stores a continuation that
        //         lets us bridge the result back into the coroutine below.
        RegisterActivityContracts {
            captureLauncher = registerForActivityResult(ScreenCaptureContract()) { _, result ->
                // Continuation is resumed in startRecordingAsync via suspendCancellableCoroutine.
                // The result is delivered through the stored continuation reference.
                pendingContinuation?.resume(result)
                pendingContinuation = null
            }
        }

        AsyncFunction("isAvailableAsync") {
            return@AsyncFunction true
        }

        // FIX 3: Use "AsyncFunction(...) Coroutine { ... }" so that the Expo runtime
        //         runs the body as a suspend function, letting us call captureLauncher.launch()
        //         and suspendCancellableCoroutine inside it.
        AsyncFunction("startRecordingAsync") Coroutine { options: RecordingOptions ->
            if (isRecording) {
                throw CodedException("ERR_ALREADY_RECORDING", "A recording sequence is already initialized.")
            }

            val context = appContext.reactContext
                ?: throw CodedException("ERR_NO_CONTEXT", "React context unavailable.")
            val activity = appContext.currentActivity
                ?: throw CodedException("ERR_NO_ACTIVITY", "Current activity missing.")
            val cacheDir = context.cacheDir
                ?: throw CodedException("ERR_NO_CONTEXT", "Cache directory is unavailable.")

            val includeAudioStream = options.includeAudio ?: false
            val qualitySetting = options.quality ?: "high"
            val outputFile = File(cacheDir, "REC_${UUID.randomUUID()}.mp4")

            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = mgr.createScreenCaptureIntent()

            // FIX 4: Bridge the activity-result callback into a coroutine via
            //         suspendCancellableCoroutine. We store the continuation so
            //         the RegisterActivityContracts callback above can resume it.
            val captureResult: ScreenCaptureResult = suspendCancellableCoroutine { cont ->
                pendingContinuation = cont
                cont.invokeOnCancellation { pendingContinuation = null }

                try {
                    captureLauncher.launch(ScreenCaptureInput(captureIntent))
                } catch (e: Exception) {
                    pendingContinuation = null
                    cont.resumeWithException(
                        CodedException("ERR_LAUNCH_FAILED", "Failed to launch screen capture intent: ${e.message}")
                    )
                }
            }

            if (captureResult.resultCode != Activity.RESULT_OK || captureResult.data == null) {
                // FIX 5: Reset isRecording on every failure path, not just the happy path.
                throw CodedException("ERR_PERMISSION_DENIED", "User declined screen recording system permissions.")
            }

            // Mark recording only after permission is granted.
            isRecording = true
            currentOutputFile = outputFile

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                activity.display?.getRealMetrics(metrics)
            } else {
                (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(metrics)
            }

            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE",   captureResult.resultCode)
                putExtra("RESULT_DATA",   captureResult.data)
                putExtra("OUTPUT_PATH",   outputFile.absolutePath)
                putExtra("WIDTH",         metrics.widthPixels)
                putExtra("HEIGHT",        metrics.heightPixels)
                putExtra("DPI",           metrics.densityDpi)
                putExtra("INCLUDE_AUDIO", includeAudioStream)
                putExtra("QUALITY",       qualitySetting)
            }

            startTime = System.currentTimeMillis()
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                // FIX 5 (cont.): Reset state if the service itself fails to start.
                isRecording = false
                currentOutputFile = null
                throw CodedException("ERR_SERVICE_START", "Failed to start recording service: ${e.message}")
            }
        }

        AsyncFunction("stopRecordingAsync") {
            val context = appContext.reactContext
                ?: throw CodedException("ERR_NO_CONTEXT", "Environment dropped.")

            context.stopService(Intent(context, ScreenCaptureService::class.java))
            isRecording = false

            val file = currentOutputFile
            if (file == null || !file.exists()) {
                throw CodedException("ERR_NO_FILE", "Output file missing or recording failed.")
            }

            val durationSec = (System.currentTimeMillis() - startTime) / 1000.0

            // Returning a Map automatically resolves the JS Promise with an Object.
            return@AsyncFunction mapOf(
                "uri"      to "file://${file.absolutePath}",
                "duration" to durationSec
            )
        }
    }

    // Holds the coroutine continuation that is waiting for the activity result.
    // Typed as nullable CancellableContinuation to support coroutine cancellation.
    private var pendingContinuation: kotlinx.coroutines.CancellableContinuation<ScreenCaptureResult>? = null
}
