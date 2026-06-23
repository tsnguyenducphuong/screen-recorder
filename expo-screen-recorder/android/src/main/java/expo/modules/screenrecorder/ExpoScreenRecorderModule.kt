package expo.modules.screenrecorder

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Typed exception
// ---------------------------------------------------------------------------

class ScreenRecorderException(
    code: String,
    message: String
) : CodedException("[$code] $message")

// ---------------------------------------------------------------------------
// Module
// ---------------------------------------------------------------------------

class ExpoScreenRecorderModule : Module() {

    private var currentOutputFile: File? = null
    private var startTime: Long = 0L

    // ✅ Fix 2: AtomicBoolean for thread-safe reads/writes across IO + Main threads
    private val isRecording = AtomicBoolean(false)

    override fun definition() = ModuleDefinition {
        Name("ExpoScreenRecorder")

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

            // ✅ Fix 2: compareAndSet ensures only one recording starts even under
            // rapid concurrent calls — atomically checks false and sets true
            if (!isRecording.compareAndSet(false, true)) {
                return@AsyncFunction promise.reject(
                    "ERR_ALREADY_RECORDING", "A recording is already in progress.", null
                )
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val outputFile = File(context.cacheDir, "REC_${UUID.randomUUID()}.mp4")
                    val includeAudioStream = options.includeAudio ?: false
                    val qualitySetting     = options.quality ?: "high"

                    val metrics = DisplayMetrics()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        // ✅ getRealMetrics on display requires main thread on API 30+
                        withContext(Dispatchers.Main) {
                            activity.display?.getRealMetrics(metrics)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        withContext(Dispatchers.Main) {
                            (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                                .defaultDisplay.getRealMetrics(metrics)
                        }
                    }

                    val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                        putExtra("OUTPUT_PATH",   outputFile.absolutePath)
                        putExtra("WIDTH",         metrics.widthPixels)
                        putExtra("HEIGHT",        metrics.heightPixels)
                        putExtra("DPI",           metrics.densityDpi)
                        putExtra("INCLUDE_AUDIO", includeAudioStream)
                        putExtra("QUALITY",       qualitySetting)
                    }

                    currentOutputFile = outputFile
                    startTime = System.currentTimeMillis()

                    // ✅ Fix 1: startForegroundService MUST be called from the main thread.
                    // Calling it from Dispatchers.IO means Android may not honour the
                    // 5-second startForeground() contract, causing the
                    // ForegroundServiceDidNotStartInTimeException crash.
                    withContext(Dispatchers.Main) {
                        context.startForegroundService(serviceIntent)
                    }

                    promise.resolve(null)

                } catch (e: Exception) {
                    // ✅ Fix 2: reset flag on failure so caller can retry
                    isRecording.set(false)
                    currentOutputFile = null
                    promise.reject("ERR_SERVICE_START", "Failed to start recording service: ${e.message}", e)
                }
            }
        }

        AsyncFunction("stopRecordingAsync") { promise: Promise ->
            val context = appContext.reactContext ?: return@AsyncFunction promise.reject(
                "ERR_NO_CONTEXT", "React context is unavailable.", null
            )

            // ✅ Fix 2: atomic check — prevents double-stop race
            if (!isRecording.compareAndSet(true, false)) {
                return@AsyncFunction promise.reject(
                    "ERR_NOT_RECORDING", "No recording is currently in progress.", null
                )
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // ✅ Fix 3: stop the service on main thread first, then wait
                    // briefly for the service's onDestroy to flush and release
                    // the MediaRecorder output before checking file existence.
                    withContext(Dispatchers.Main) {
                        context.stopService(Intent(context, ScreenCaptureService::class.java))
                    }

                    // ✅ Fix 3: give the service's onDestroy + MediaRecorder.stop()
                    // time to flush the MP4 moov atom to disk before we stat the file.
                    // 300ms is conservative — onDestroy is synchronous on the main thread
                    // so by the time we resume, the file should be fully written.
                    kotlinx.coroutines.delay(300L)

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
                    // ✅ Restore flag so caller can retry stop
                    isRecording.set(true)
                    promise.reject("ERR_STOP_RECORDING", e.message, e)
                }
            }
        }
    }
}
 