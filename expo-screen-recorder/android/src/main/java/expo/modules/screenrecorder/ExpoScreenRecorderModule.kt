package expo.modules.screenrecorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContract
import expo.modules.kotlin.activityresult.AppContextActivityResultCaller
import expo.modules.kotlin.activityresult.ActivityResultContractSenderAndroidX
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import java.io.File
import java.util.UUID

// ── Contract ──────────────────────────────────────────────────────────────────
// Wraps MediaProjectionManager.createScreenCaptureIntent() in the AndroidX
// Activity Result API that Expo SDK 54 modules are expected to use.
class ScreenCaptureContract : ActivityResultContract<Unit, Pair<Int, Intent?>>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Pair<Int, Intent?> =
        resultCode to intent
}

// ── Module ────────────────────────────────────────────────────────────────────
class ExpoScreenRecorderModule : Module() {

    private var pendingPromise: Promise? = null
    private var currentOutputFile: File? = null
    private var startTime: Long = 0L
    private var includeAudioStream: Boolean = false
    private var qualitySetting: String = "high"

    // Launcher is initialised once inside OnCreate and reused for every recording
    // request. It is torn down automatically when the module is destroyed because
    // ActivityResultContractSenderAndroidX is scoped to the module lifecycle.
    private lateinit var captureLauncher: ActivityResultContractSenderAndroidX<Unit, Pair<Int, Intent?>>

    override fun definition() = ModuleDefinition {
        Name("ExpoScreenRecorder")

        // ── Lifecycle ─────────────────────────────────────────────────────────

        OnCreate {
            // Register the contract once. The returned sender is our handle for
            // launching the system permission dialog on every startRecordingAsync call.
            captureLauncher = appContext
                .registerForActivityResult(ScreenCaptureContract()) { (resultCode, data) ->
                    handleCaptureResult(resultCode, data)
                }
        }

        OnDestroy {
            // Unregister the launcher so no stale callbacks fire after the
            // module is torn down (e.g. fast-refresh, app backgrounding).
            captureLauncher.unregister()

            // If the module is destroyed mid-recording, reject the pending
            // promise rather than leaving the caller hanging forever.
            pendingPromise?.reject(
                "ERR_MODULE_DESTROYED",
                "Screen recorder module was destroyed before the operation completed.",
                null
            )
            pendingPromise = null
        }

        // ── API ───────────────────────────────────────────────────────────────

        AsyncFunction("isAvailableAsync") {
            return@AsyncFunction true
        }

        AsyncFunction("startRecordingAsync") { options: RecordingOptions, promise: Promise ->
            if (pendingPromise != null) {
                promise.reject(
                    "ERR_ALREADY_RECORDING",
                    "A recording sequence is already initialised.",
                    null
                )
                return@AsyncFunction
            }

            val cacheDir = appContext.reactContext?.cacheDir
            if (cacheDir == null) {
                promise.reject("ERR_NO_CONTEXT", "Cache directory is unavailable.", null)
                return@AsyncFunction
            }

            pendingPromise       = promise
            includeAudioStream   = options.includeAudio ?: false
            qualitySetting       = options.quality ?: "high"
            currentOutputFile    = File(cacheDir, "REC_${UUID.randomUUID()}.mp4")

            // Launch the system screen-capture permission dialog.
            // The result lands in handleCaptureResult() via the contract callback.
            captureLauncher.launch(
                input         = Unit,
                onFailure     = { ex ->
                    pendingPromise?.reject("ERR_LAUNCH_FAILED", ex.message, ex)
                    pendingPromise = null
                }
            )
        }

        AsyncFunction("stopRecordingAsync") { promise: Promise ->
            val context = appContext.reactContext ?: run {
                promise.reject(
                    "ERR_NO_CONTEXT",
                    "Application execution environment dropped.",
                    null
                )
                return@AsyncFunction
            }

            context.stopService(Intent(context, ScreenCaptureService::class.java))

            val file = currentOutputFile
            if (file == null || !file.exists()) {
                promise.reject("ERR_NO_FILE", "Output file missing or recording failed.", null)
                return@AsyncFunction
            }

            val durationSec = (System.currentTimeMillis() - startTime) / 1000.0
            promise.resolve(
                mapOf(
                    "uri"      to "file://${file.absolutePath}",
                    "duration" to durationSec
                )
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun handleCaptureResult(resultCode: Int, data: Intent?) {
        val promise = pendingPromise

        if (resultCode != Activity.RESULT_OK || data == null) {
            promise?.reject(
                "ERR_PERMISSION_DENIED",
                "User declined screen recording system permissions.",
                null
            )
            pendingPromise = null
            return
        }

        val context = appContext.reactContext ?: run {
            promise?.reject("ERR_NO_CONTEXT", "React context unavailable.", null)
            pendingPromise = null
            return
        }

        val activity = appContext.currentActivity ?: run {
            promise?.reject("ERR_NO_ACTIVITY", "Current activity context is missing.", null)
            pendingPromise = null
            return
        }

        // Resolve screen dimensions for the capture service
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.getRealMetrics(metrics)
        } else {
            (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)
        }

        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            putExtra("RESULT_CODE",   resultCode)
            putExtra("RESULT_DATA",   data)
            putExtra("OUTPUT_PATH",   currentOutputFile?.absolutePath)
            putExtra("WIDTH",         metrics.widthPixels)
            putExtra("HEIGHT",        metrics.heightPixels)
            putExtra("DPI",           metrics.densityDpi)
            putExtra("INCLUDE_AUDIO", includeAudioStream)
            putExtra("QUALITY",       qualitySetting)
        }

        startTime = System.currentTimeMillis()
        context.startForegroundService(serviceIntent)

        // Resolve startRecordingAsync — the caller now awaits stopRecordingAsync.
        promise?.resolve(null)
        pendingPromise = null
    }
}