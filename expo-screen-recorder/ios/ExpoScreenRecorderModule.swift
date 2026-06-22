import ExpoModulesCore
import ReplayKit
import AVFoundation

public class ExpoScreenRecorderModule: Module {
    private let recorder = RPScreenRecorder.shared()
    private var startTime: Date?
    private var durationTimer: Timer?
    private var currentOutputURL: URL?

    private enum RecordingState { case idle, starting, recording, stopping }
    private var currentState: RecordingState = .idle
    private let stateLock = NSLock()

    // sessionQueue-local stop signal — only written via sessionQueue.sync
    private var isStopping = false
    private var includeAudioCapture = false

    // ✅ Fix 2: store promise from stop-during-start scenario
    private var pendingStopPromise: Promise?

    private var assetWriter: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var isWritingStarted = false
    private let sessionQueue = DispatchQueue(label: "expo.modules.screenrecorder.sessionQueue")

    deinit {
        durationTimer?.invalidate()
        stateLock.lock()
        let needsStop = (currentState == .recording || currentState == .starting)
        stateLock.unlock()
        if needsStop { recorder.stopCapture { _ in } }
        assetWriter?.cancelWriting()
    }

    public func definition() -> ModuleDefinition {
        Name("ExpoScreenRecorder")

        Events("onRecordingFinished")

        AsyncFunction("isAvailableAsync") { () -> Bool in
            return self.recorder.isAvailable
        }

        AsyncFunction("startRecordingAsync") { (options: RecordingOptions, promise: Promise) in
            guard self.recorder.isAvailable else {
                promise.reject("ERR_NOT_AVAILABLE", "Screen recorder is not available.")
                return
            }

            self.stateLock.lock()
            guard self.currentState == .idle else {
                self.stateLock.unlock()
                promise.reject("ERR_ALREADY_RECORDING", "A recording or teardown is already in progress.")
                return
            }
            self.currentState = .starting
            self.stateLock.unlock()

            // ✅ Fix 1: isStopping reset outside stateLock, on sessionQueue
            self.sessionQueue.sync { self.isStopping = false }

            let includeAudio = options.includeAudio ?? false
            self.includeAudioCapture = includeAudio
            self.recorder.isMicrophoneEnabled = includeAudio

            let cacheDirectory = FileManager.default.urls(
                for: .cachesDirectory, in: .userDomainMask
            ).first!
            let outputURL = cacheDirectory.appendingPathComponent("REC_\(UUID().uuidString).mp4")
            self.currentOutputURL = outputURL

            // ✅ Deadlock-safe UIScreen read
            let (bounds, scale): (CGRect, CGFloat)
            if Thread.isMainThread {
                bounds = UIScreen.main.bounds
                scale = UIScreen.main.scale
            } else {
                (bounds, scale) = DispatchQueue.main.sync {
                    (UIScreen.main.bounds, UIScreen.main.scale)
                }
            }

            let pixelWidth  = Int(bounds.width  * scale)
            let pixelHeight = Int(bounds.height * scale)
            let alignedWidth  = (pixelWidth  / 2) * 2
            let alignedHeight = (pixelHeight / 2) * 2

            let bitRate: Int
            switch options.quality ?? "high" {
            case "low":    bitRate = 2_000_000
            case "medium": bitRate = 4_000_000
            default:       bitRate = 8_000_000
            }

            do {
                self.assetWriter = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)

                let videoSettings: [String: Any] = [
                    AVVideoCodecKey: AVVideoCodecType.h264,
                    AVVideoWidthKey: alignedWidth,
                    AVVideoHeightKey: alignedHeight,
                    AVVideoCompressionPropertiesKey: [
                        AVVideoAverageBitRateKey: bitRate,
                        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
                    ]
                ]
                self.videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
                self.videoInput?.expectsMediaDataInRealTime = true
                if let videoInput = self.videoInput,
                   self.assetWriter?.canAdd(videoInput) == true {
                    self.assetWriter?.add(videoInput)
                }

                if includeAudio {
                    let audioSettings: [String: Any] = [
                        AVFormatIDKey: kAudioFormatMPEG4AAC,
                        AVNumberOfChannelsKey: 2,
                        AVSampleRateKey: 44100.0,
                        AVEncoderBitRateKey: 128000
                    ]
                    self.audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
                    self.audioInput?.expectsMediaDataInRealTime = true
                    if let audioInput = self.audioInput,
                       self.assetWriter?.canAdd(audioInput) == true {
                        self.assetWriter?.add(audioInput)
                    }
                }

                self.assetWriter?.startWriting()
                self.isWritingStarted = false

            } catch {
                self.stateLock.lock()
                self.currentState = .idle
                self.stateLock.unlock()
                promise.reject("ERR_WRITER_INIT_FAILED", error.localizedDescription)
                return
            }

            self.recorder.startCapture(handler: { [weak self] sampleBuffer, bufferType, error in
                guard let self = self else { return }
                if let error = error {
                    print("Buffer capture error: \(error)")
                    return
                }

                self.sessionQueue.async {
                    guard CMSampleBufferDataIsReady(sampleBuffer), !self.isStopping else { return }

                    if !self.isWritingStarted && bufferType == .video {
                        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
                        self.assetWriter?.startSession(atSourceTime: pts)
                        self.isWritingStarted = true
                    }

                    guard self.isWritingStarted else { return }

                    if bufferType == .video,
                       self.videoInput?.isReadyForMoreMediaData == true {
                        self.videoInput?.append(sampleBuffer)
                    } else if bufferType == .audioMic,
                              self.includeAudioCapture,
                              self.audioInput?.isReadyForMoreMediaData == true {
                        self.audioInput?.append(sampleBuffer)
                    }
                }
            }, completionHandler: { [weak self] error in
                guard let self = self else { return }

                self.stateLock.lock()
                if let error = error {
                    self.currentState = .idle
                    self.stateLock.unlock()
                    promise.reject("ERR_START_FAILED", error.localizedDescription)
                    return
                }

                // ✅ Fix 2: if stop was requested during starting,
                // hand off the stored stop promise to the pipeline
                if self.currentState == .stopping {
                    let stopPromise = self.pendingStopPromise
                    self.pendingStopPromise = nil
                    self.stateLock.unlock()
                    promise.resolve(nil)  // startRecordingAsync resolves — recording briefly started
                    self.executeStopPipeline(explicitPromise: stopPromise)
                    return
                }

                self.currentState = .recording
                self.startTime = Date()
                self.stateLock.unlock()
                promise.resolve(nil)

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
            })
        }

        AsyncFunction("stopRecordingAsync") { (promise: Promise) in
            self.durationTimer?.invalidate()
            self.durationTimer = nil

            self.stateLock.lock()
            guard self.currentState == .recording || self.currentState == .starting else {
                self.stateLock.unlock()
                promise.reject("ERR_NOT_RECORDING", "No active recording to stop.")
                return
            }

            if self.currentState == .starting {
                // ✅ Fix 2: store promise — pipeline resolves it once start completes
                self.currentState = .stopping
                self.pendingStopPromise = promise
                self.stateLock.unlock()
                // ✅ Fix 1: release lock before sessionQueue.sync
                self.sessionQueue.sync { self.isStopping = true }
                return
            }

            self.currentState = .stopping
            self.stateLock.unlock()
            // ✅ Fix 1: lock released before sessionQueue.sync
            self.sessionQueue.sync { self.isStopping = true }
            self.executeStopPipeline(explicitPromise: promise)
        }
    }

    private func stopRecordingInternal() {
        stateLock.lock()
        guard currentState == .recording else {
            stateLock.unlock()
            return
        }
        currentState = .stopping
        stateLock.unlock()
        // ✅ Fix 1: lock released before sessionQueue.sync
        sessionQueue.sync { self.isStopping = true }
        executeStopPipeline(explicitPromise: nil)
    }

    private func executeStopPipeline(explicitPromise: Promise?) {
        recorder.stopCapture { [weak self] error in
            guard let self = self else { return }

            if let error = error {
                explicitPromise?.reject("ERR_STOP_FAILED", error.localizedDescription)
                self.cleanup()
                return
            }

            self.sessionQueue.async {
                self.videoInput?.markAsFinished()
                self.audioInput?.markAsFinished()

                self.assetWriter?.finishWriting { [weak self] in
                    guard let self = self else { return }
                    // ✅ Fix 4: always evaluate result and cleanup on sessionQueue
                    self.sessionQueue.async {
                        if self.assetWriter?.status == .failed {
                            explicitPromise?.reject(
                                "ERR_WRITE_FAILED",
                                self.assetWriter?.error?.localizedDescription ?? "Unknown write error."
                            )
                            self.cleanup()
                            return
                        }

                        let durationSec = Date().timeIntervalSince(self.startTime ?? Date())
                        let path = self.currentOutputURL?.path ?? ""
                        let payload: [String: Any] = [
                            "uri": "file://\(path)",
                            "duration": durationSec
                        ]

                        if let promise = explicitPromise {
                            promise.resolve(payload)
                        } else {
                            self.sendEvent("onRecordingFinished", payload)
                        }
                        self.cleanup()
                    }
                }
            }
        }
    }

    private func cleanup() {
        // ✅ Fix 4: sessionQueue-owned state cleared on sessionQueue
        sessionQueue.async {
            self.assetWriter = nil
            self.videoInput = nil
            self.audioInput = nil
            self.isWritingStarted = false
            self.isStopping = false
            self.includeAudioCapture = false
        }
        // Timer must be invalidated on main thread
        DispatchQueue.main.async {
            self.durationTimer?.invalidate()
            self.durationTimer = nil
        }
        startTime = nil
        currentOutputURL = nil
        pendingStopPromise = nil
        stateLock.lock()
        currentState = .idle
        stateLock.unlock()
    }
}

struct RecordingOptions: Record {
    @Field var durationLimit: Double?
    @Field var quality: String?
    @Field var includeAudio: Bool?
}