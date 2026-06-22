package expo.modules.screenrecorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContract
import expo.modules.kotlin.activityresult.AppContextActivityResultLauncher
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.io.File
import java.util.UUID

 

// ── Contract ──────────────────────────────────────────────────────────────────
class ScreenCaptureContract : ActivityResultContract<Unit, Pair<Int, Intent?>>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Pair<Int, Intent?> =
        Pair(resultCode, intent)
}

// ── Module ────────────────────────────────────────────────────────────────────
class ExpoScreenRecorderModule : Module() {

    private var pendingPromise: Promise? = null
    private var currentOutputFile: File? = null
    private var startTime: Long = 0L
    private var includeAudioStream: Boolean = false
    private var qualitySetting: String = "high"

    // ✅ FIX 1: Use the correct AppContextActivityResultLauncher type
    private lateinit var captureLauncher: AppContextActivityResultLauncher<Unit, Pair<Int, Intent?>>

    override fun definition() = ModuleDefinition {
        Name("ExpoScreenRecorder")

        OnCreate {
            captureLauncher = appContext
                .registerForActivityResult(ScreenCaptureContract()) { result ->
                    handleCaptureResult(result.first, result.second)
                }
        }

        OnDestroy {
            // ✅ FIX 2: Removed appContext.unregisterForActivityResult() as it's handled internally
            pendingPromise?.reject(
                "ERR_MODULE_DESTROYED",
                "Screen recorder module was destroyed before the operation completed.",
                null
            )
            pendingPromise = null
        }

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

            pendingPromise     = promise
            includeAudioStream = options.includeAudio ?: false
            qualitySetting     = options.quality ?: "high"
            currentOutputFile  = File(cacheDir, "REC_${UUID.randomUUID()}.mp4")

            try {
                captureLauncher.launch(Unit)
            } catch (ex: Exception) {
                pendingPromise?.reject("ERR_LAUNCH_FAILED", ex.message ?: "Launch failed.", null)
                pendingPromise = null
            }
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

            // Note: ScreenCaptureService must be implemented elsewhere in your project
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

        val metrics = DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
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

        promise?.resolve(null)
        pendingPromise = null
    }
}