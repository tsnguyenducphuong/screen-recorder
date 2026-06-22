package expo.modules.screenrecorder

// import android.app.Activity                                   // Unused: permission result check handled on the JS side
import android.content.Context
import android.content.Intent
// import android.media.projection.MediaProjectionManager        // Unused: screen capture intent built on the JS side
import android.util.DisplayMetrics
import android.view.WindowManager
import expo.modules.kotlin.Promise
// import expo.modules.kotlin.activityresult.AppContextActivityResultContract  // Unused: no activity contract needed when JS owns permission
// import expo.modules.kotlin.activityresult.AppContextActivityResultLauncher  // Unused: no activity contract needed when JS owns permission
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
// import kotlinx.coroutines.suspendCancellableCoroutine         // Unused: only needed by requestScreenCapturePermission
import java.io.File
// import java.io.Serializable                                   // Unused: ScreenCaptureInput no longer needed
import java.util.UUID
// import kotlin.coroutines.resume                               // Unused: only needed by requestScreenCapturePermission
// import kotlin.coroutines.resumeWithException                  // Unused: only needed by requestScreenCapturePermission

 
// ---------------------------------------------------------------------------
// Typed exception
// ---------------------------------------------------------------------------

class ScreenRecorderException(
    override val code: String,
    message: String
) : CodedException(message)

// ---------------------------------------------------------------------------
// Activity result contract types
// NOTE: Permission is handled on the JS side — these types are kept for
// reference but are not used at runtime.
// ---------------------------------------------------------------------------

// data class ScreenCaptureInput(
//     val captureIntent: Intent
// ) : Serializable
//
// data class ScreenCaptureResult(
//     val resultCode: Int,
//     val data: Intent?
// )
//
// class ScreenCaptureContract :
//     AppContextActivityResultContract<ScreenCaptureInput, ScreenCaptureResult> {
//
//     override fun createIntent(context: Context, input: ScreenCaptureInput): Intent =
//         input.captureIntent
//
//     override fun parseResult(
//         input: ScreenCaptureInput,
//         resultCode: Int,
//         intent: Intent?
//     ): ScreenCaptureResult = ScreenCaptureResult(resultCode, intent)
// }

// ---------------------------------------------------------------------------
// Module
// ---------------------------------------------------------------------------

class ExpoScreenRecorderModule : Module() {

    // private lateinit var captureLauncher: AppContextActivityResultLauncher<
    //     ScreenCaptureInput,
    //     ScreenCaptureResult
    // >

    // private var pendingContinuation: kotlinx.coroutines.CancellableContinuation<ScreenCaptureResult>? = null
    private var currentOutputFile: File? = null
    private var startTime: Long = 0L
    private var isRecording: Boolean = false

    override fun definition() = ModuleDefinition {
        Name("ExpoScreenRecorder")

        // NOTE: RegisterActivityContracts is commented out — permission is handled
        // on the JS side, so no activity result contract is needed here.
        // RegisterActivityContracts {
        //     captureLauncher = registerForActivityResult(ScreenCaptureContract()) { result ->
        //         pendingContinuation?.resume(result)
        //         pendingContinuation = null
        //     }
        // }

        // ---------------------------------------------------------------------------
        // isAvailableAsync — trivial synchronous check wrapped in the same
        // Promise + CoroutineScope pattern for consistency.
        // ---------------------------------------------------------------------------

        AsyncFunction("isAvailableAsync") { promise: Promise ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    promise.resolve(true)
                } catch (e: Exception) {
                    promise.reject("ERR_AVAILABILITY_CHECK", e.message, e)
                }
            }
        }

        // ---------------------------------------------------------------------------
        // startRecordingAsync — requests the system screen-capture permission,
        // then hands off to the foreground service.
        // ---------------------------------------------------------------------------

        AsyncFunction("startRecordingAsync") { options: RecordingOptions, promise: Promise ->
            val context = appContext.reactContext ?: return@AsyncFunction promise.reject(
                "ERR_NO_CONTEXT", "React context is unavailable.", null
            )
            val activity = appContext.currentActivity ?: return@AsyncFunction promise.reject(
                "ERR_NO_ACTIVITY", "Current activity is missing.", null
            )

            if (isRecording) {
                return@AsyncFunction promise.reject(
                    "ERR_ALREADY_RECORDING", "A recording is already in progress.", null
                )
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // NOTE: Permission is handled on the JS side before this function is called.
                    // The captureResult (resultCode + data Intent) must be passed in from JS
                    // once the JS layer obtains it via MediaProjection APIs.
                    //
                    // val captureResult = requestScreenCapturePermission(context)
                    // if (captureResult.resultCode != Activity.RESULT_OK || captureResult.data == null) {
                    //     promise.reject("ERR_PERMISSION_DENIED", "User declined screen recording permission.", null)
                    //     return@launch
                    // }

                    val outputFile = File(context.cacheDir, "REC_${UUID.randomUUID()}.mp4")
                    val includeAudioStream = options.includeAudio ?: false
                    val qualitySetting = options.quality ?: "high"

                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        activity.display?.getRealMetrics(metrics)
                    } else {
                        (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .defaultDisplay.getRealMetrics(metrics)
                    }

                    val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                        // putExtra("RESULT_CODE", captureResult.resultCode)  // Provided by JS layer
                        // putExtra("RESULT_DATA", captureResult.data)        // Provided by JS layer
                        putExtra("OUTPUT_PATH",   outputFile.absolutePath)
                        putExtra("WIDTH",         metrics.widthPixels)
                        putExtra("HEIGHT",        metrics.heightPixels)
                        putExtra("DPI",           metrics.densityDpi)
                        putExtra("INCLUDE_AUDIO", includeAudioStream)
                        putExtra("QUALITY",       qualitySetting)
                    }

                    isRecording = true
                    currentOutputFile = outputFile
                    startTime = System.currentTimeMillis()

                    try {
                        context.startForegroundService(serviceIntent)
                        promise.resolve(null)
                    } catch (e: Exception) {
                        isRecording = false
                        currentOutputFile = null
                        promise.reject("ERR_SERVICE_START", "Failed to start recording service: ${e.message}", e)
                    }

                } catch (e: Exception) {
                    promise.reject("ERR_START_RECORDING", e.message, e)
                }
            }
        }

        // ---------------------------------------------------------------------------
        // stopRecordingAsync — stops the service and resolves with file URI + duration.
        // ---------------------------------------------------------------------------

        AsyncFunction("stopRecordingAsync") { promise: Promise ->
            val context = appContext.reactContext ?: return@AsyncFunction promise.reject(
                "ERR_NO_CONTEXT", "React context is unavailable.", null
            )

            if (!isRecording) {
                return@AsyncFunction promise.reject(
                    "ERR_NOT_RECORDING", "No recording is currently in progress.", null
                )
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    context.stopService(Intent(context, ScreenCaptureService::class.java))
                    isRecording = false

                    val file = currentOutputFile
                    if (file == null || !file.exists()) {
                        promise.reject(
                            "ERR_NO_FILE",
                            "Output file is missing or the recording failed.",
                            null
                        )
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
                    promise.reject("ERR_STOP_RECORDING", e.message, e)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // NOTE: requestScreenCapturePermission is commented out — permission is
    // handled on the JS side. Kept here for reference if the flow ever moves
    // back to native.
    // ---------------------------------------------------------------------------

    // private suspend fun requestScreenCapturePermission(context: Context): ScreenCaptureResult {
    //     val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    //     val captureIntent = mgr.createScreenCaptureIntent()
    //
    //     return suspendCancellableCoroutine { cont ->
    //         pendingContinuation = cont
    //         cont.invokeOnCancellation { pendingContinuation = null }
    //
    //         try {
    //             captureLauncher.launch(ScreenCaptureInput(captureIntent))
    //         } catch (e: Exception) {
    //             pendingContinuation = null
    //             cont.resumeWithException(
    //                 ScreenRecorderException(
    //                     "ERR_LAUNCH_FAILED",
    //                     "Failed to launch screen capture intent: ${e.message}"
    //                 )
    //             )
    //         }
    //     }
    // }
}