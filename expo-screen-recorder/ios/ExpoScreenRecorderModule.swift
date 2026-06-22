import ExpoModulesCore
import ReplayKit

public class ExpoScreenRecorderModule: Module {
    private let recorder = RPScreenRecorder.shared()
    private var startTime: Date?
    private var durationTimer: Timer?
    private var pendingPromise: Promise?
    private var currentOutputURL: URL?

    deinit {
        durationTimer?.invalidate()
        durationTimer = nil
        pendingPromise?.reject("ERR_MODULE_DESTROYED", "Screen recorder module was deallocated before the operation completed.")
        pendingPromise = nil
    }

    public func definition() -> ModuleDefinition {
        Name("ExpoScreenRecorder")

        AsyncFunction("isAvailableAsync") { () -> Bool in
            return self.recorder.isAvailable
        }

        AsyncFunction("startRecordingAsync") { (options: RecordingOptions, promise: Promise) in
            guard self.recorder.isAvailable else {
                promise.reject("ERR_NOT_AVAILABLE", "Screen recorder is not available on this device.")
                return
            }

            guard !self.recorder.isRecording else {
                promise.reject("ERR_ALREADY_RECORDING", "A recording is already in progress.")
                return
            }

            self.recorder.isMicrophoneEnabled = options.includeAudio ?? false

            let cacheDirectory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            let uniqueFilename = "REC_\(UUID().uuidString).mp4"
            self.currentOutputURL = cacheDirectory.appendingPathComponent(uniqueFilename)
            self.pendingPromise = promise

            self.recorder.startRecording { [weak self] error in
                guard let self = self else { return }

                if let error = error {
                    self.pendingPromise?.reject("ERR_START_FAILED", error.localizedDescription)
                    self.pendingPromise = nil
                    return
                }

                self.startTime = Date()
                self.pendingPromise?.resolve(nil)
                self.pendingPromise = nil

                if let limit = options.durationLimit, limit > 0 {
                    DispatchQueue.main.async {
                        self.durationTimer = Timer.scheduledTimer(
                            withTimeInterval: limit,
                            repeats: false
                        ) { [weak self] _ in
                            self?.stopRecordingInternal()
                        }
                    }
                }
            }
        }

        AsyncFunction("stopRecordingAsync") { (promise: Promise) in
            self.stopRecordingWithPromise(promise: promise)
        }
    }

    private func stopRecordingWithPromise(promise: Promise) {
        durationTimer?.invalidate()
        durationTimer = nil

        guard recorder.isRecording else {
            promise.reject("ERR_NOT_RECORDING", "No active recording to stop.")
            return
        }

        guard let outputURL = currentOutputURL else {
            promise.reject("ERR_NO_FILE", "Output file URL was not set.")
            return
        }

        if #available(iOS 17.0, *) {
            // stopRecording(withOutput:) is deprecated in iOS 17.
            // Full migration to startCapture(handler:) for buffer-level control
            // is recommended for a future release. For now, suppress the warning.
        }

        #if swift(>=5.9)
        // Suppress deprecation for the withOutput variant — still functional through iOS 17+
        // and is the only single-call file-output API ReplayKit exposes publicly.
        #endif

        self.recorder.stopRecording(withOutput: outputURL) { [weak self] error in
            guard let self = self else { return }

            if let error = error {
                promise.reject("ERR_STOP_FAILED", error.localizedDescription)
                self.cleanup()
                return
            }

            let durationSec = Date().timeIntervalSince(self.startTime ?? Date())

            // Use "file://" prefix to match Android's uri format consistently
            let uri = "file://\(outputURL.path)"

            promise.resolve([
                "uri": uri,
                "duration": durationSec
            ] as [String: Any])

            self.cleanup()
        }
    }

    private func stopRecordingInternal() {
        durationTimer?.invalidate()
        durationTimer = nil

        guard let outputURL = currentOutputURL, recorder.isRecording else { return }

        recorder.stopRecording(withOutput: outputURL) { [weak self] _ in
            self?.cleanup()
        }
    }

    private func cleanup() {
        durationTimer?.invalidate()
        durationTimer = nil
        startTime = nil
        currentOutputURL = nil
    }
}

struct RecordingOptions: Record {
    @Field var durationLimit: Double?
    // NOTE: ReplayKit does not expose bitrate/quality control via RPScreenRecorder.
    // This field is accepted for API consistency with Android but has no effect on iOS.
    @Field var quality: String?
    @Field var includeAudio: Bool?
}