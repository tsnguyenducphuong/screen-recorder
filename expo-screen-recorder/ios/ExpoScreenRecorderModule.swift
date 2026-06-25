import ExpoModulesCore
import ReplayKit
import AVFoundation

public class ExpoScreenRecorderModule: Module {
    private let recorder = RPScreenRecorder.shared()

    private enum RecordingState { case idle, starting, recording, stopping }
    private var currentState: RecordingState = .idle
    private let stateLock = NSLock()

    private var pendingStopPromise: Promise?

    // ─── sessionQueue-owned state ────────────────────────────────────────────
    // These vars are ONLY read and written on sessionQueue. Never touch them
    // from any other thread/queue.
    private let sessionQueue = DispatchQueue(label: "expo.modules.screenrecorder.sessionQueue")
    private var isStopping        = false
    private var isWritingStarted  = false
    private var includeAudioCapture = false
    private var assetWriter:  AVAssetWriter?
    private var videoInput:   AVAssetWriterInput?
    private var audioInput:   AVAssetWriterInput?
    private var startTime:    Date?
    private var currentOutputURL: URL?
    // Dimensions and bitRate are set once from the first video sample buffer,
    // eliminating the UIScreen.main deprecation and giving exact ReplayKit dimensions.
    private var pendingBitRate: Int = 8_000_000
    private var writerConfigured: Bool = false
    // ────────────────────────────────────────────────────────────────────────

    // main-thread-owned state
    private var durationTimer: Timer?

    deinit {
        // Invalidate timer on main thread where it was created.
        if Thread.isMainThread {
            durationTimer?.invalidate()
        } else {
            DispatchQueue.main.async { self.durationTimer?.invalidate() }
        }
        stateLock.lock()
        let needsStop = (currentState == .recording || currentState == .starting)
        stateLock.unlock()
        if needsStop { recorder.stopCapture { _ in } }
        // cancelWriting is documented as thread-safe.
        sessionQueue.async { self.assetWriter?.cancelWriting() }
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

            // Reset stop flag on its owning queue before any capture begins.
            self.sessionQueue.sync { self.isStopping = false }

            let includeAudio = options.includeAudio ?? false
            self.recorder.isMicrophoneEnabled = includeAudio

            // FIX 1: Guard against nil cacheDir instead of force-unwrapping.
            guard let cacheDirectory = FileManager.default.urls(
                for: .cachesDirectory, in: .userDomainMask
            ).first else {
                self.stateLock.lock()
                self.currentState = .idle
                self.stateLock.unlock()
                promise.reject("ERR_NO_CACHE_DIR", "Could not locate app cache directory.")
                return
            }
            let outputURL = cacheDirectory.appendingPathComponent("REC_\(UUID().uuidString).mp4")

            let bitRate: Int
            switch options.quality ?? "high" {
            case "low":    bitRate = 2_000_000
            case "medium": bitRate = 4_000_000
            default:       bitRate = 8_000_000
            }

            // Build writer before startCapture. Video input is added lazily on the
            // first sample buffer so we get exact dimensions from ReplayKit itself,
            // avoiding UIScreen.main (deprecated iOS 16+) entirely.
            let writer: AVAssetWriter
            var aInput: AVAssetWriterInput? = nil

            do {
                writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)

                if includeAudio {
                    let audioSettings: [String: Any] = [
                        AVFormatIDKey: kAudioFormatMPEG4AAC,
                        AVNumberOfChannelsKey: 2,
                        AVSampleRateKey: 44100.0,
                        AVEncoderBitRateKey: 128_000
                    ]
                    let ai = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
                    ai.expectsMediaDataInRealTime = true
                    if writer.canAdd(ai) { writer.add(ai) }
                    aInput = ai
                }

                writer.startWriting()

                // Assign all sessionQueue-owned state on sessionQueue — single point
                // of assignment, serialized before startCapture begins delivering buffers.
                self.sessionQueue.sync {
                    self.assetWriter         = writer
                    self.videoInput          = nil      // configured on first video frame
                    self.audioInput          = aInput
                    self.isWritingStarted    = false
                    self.writerConfigured    = false
                    self.includeAudioCapture = includeAudio
                    self.currentOutputURL    = outputURL
                    self.startTime           = nil
                    self.pendingBitRate      = bitRate
                }

            } catch {
                self.stateLock.lock()
                self.currentState = .idle
                self.stateLock.unlock()
                promise.reject("ERR_WRITER_INIT_FAILED", error.localizedDescription)
                return
            }

            self.recorder.startCapture(
                handler: { [weak self] sampleBuffer, bufferType, error in
                    guard let self = self else { return }
                    if let error = error {
                        print("[ScreenRecorder] Buffer capture error: \(error)")
                        return
                    }

                    self.sessionQueue.async {
                        // isStopping is only written on sessionQueue so this read
                        // is race-free. Any append blocks queued before isStopping
                        // was set will drain before markAsFinished() runs because
                        // sessionQueue is serial and markAsFinished is posted here too.
                        guard CMSampleBufferDataIsReady(sampleBuffer),
                              !self.isStopping else { return }

                        // Lazily configure and add the video input on the FIRST video
                        // frame. This gives us exact pixel dimensions directly from
                        // ReplayKit — no UIScreen.main needed (which is deprecated
                        // in iOS 16+). Dimensions are always even-aligned and non-zero.
                        if !self.writerConfigured && bufferType == .video {
                            if let fmt = CMSampleBufferGetFormatDescription(sampleBuffer) {
                                let dims = CMVideoFormatDescriptionGetDimensions(fmt)
                                let w = max((Int(dims.width)  / 2) * 2, 2)
                                let h = max((Int(dims.height) / 2) * 2, 2)
                                let videoSettings: [String: Any] = [
                                    AVVideoCodecKey: AVVideoCodecType.h264,
                                    AVVideoWidthKey: w,
                                    AVVideoHeightKey: h,
                                    AVVideoCompressionPropertiesKey: [
                                        AVVideoAverageBitRateKey: self.pendingBitRate,
                                        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
                                    ]
                                ]
                                let vIn = AVAssetWriterInput(mediaType: .video,
                                                             outputSettings: videoSettings)
                                vIn.expectsMediaDataInRealTime = true
                                if self.assetWriter?.canAdd(vIn) == true {
                                    self.assetWriter?.add(vIn)
                                    self.videoInput = vIn
                                }
                            }
                            self.writerConfigured = true
                        }

                        if !self.isWritingStarted && bufferType == .video && self.writerConfigured {
                            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
                            self.assetWriter?.startSession(atSourceTime: pts)
                            self.isWritingStarted = true
                            self.startTime = Date()
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
                },
                completionHandler: { [weak self] error in
                    // FIX 3: completionHandler has [weak self] — confirmed.
                    // stateLock is acquired ONCE and held across the entire branch
                    // tree so currentState cannot change mid-read. Every branch
                    // has a matching unlock — no lock leak.
                    guard let self = self else { return }

                    self.stateLock.lock()

                    if let error = error {
                        // startCapture itself failed.
                        self.currentState = .idle
                        self.stateLock.unlock()
                        promise.reject("ERR_START_FAILED", error.localizedDescription)
                        self.cleanupOnSessionQueue()
                        return
                    }

                    if self.currentState == .stopping {
                        // stopRecordingAsync arrived while startCapture was in flight.
                        // Resolve start, then hand off to the stop pipeline.
                        let stopPromise = self.pendingStopPromise
                        self.pendingStopPromise = nil
                        self.stateLock.unlock()
                        promise.resolve(nil)
                        self.executeStopPipeline(explicitPromise: stopPromise)
                        return
                    }

                    self.currentState = .recording
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
                }
            )
        }

        AsyncFunction("stopRecordingAsync") { (promise: Promise) in
            // Timer was created on the main thread and must be invalidated there.
            DispatchQueue.main.async {
                self.durationTimer?.invalidate()
                self.durationTimer = nil
            }

            self.stateLock.lock()
            guard self.currentState == .recording || self.currentState == .starting else {
                self.stateLock.unlock()
                promise.reject("ERR_NOT_RECORDING", "No active recording to stop.")
                return
            }

            if self.currentState == .starting {
                // startCapture completionHandler hasn't fired yet.
                // Store the promise; it is consumed there.
                self.currentState = .stopping
                self.pendingStopPromise = promise
                self.stateLock.unlock()
                // Lock released BEFORE sessionQueue.sync — prevents the classic
                // lock-A-then-wait-for-B / lock-B-then-wait-for-A deadlock.
                self.sessionQueue.sync { self.isStopping = true }
                return
            }

            self.currentState = .stopping
            self.stateLock.unlock()
            self.sessionQueue.sync { self.isStopping = true }
            self.executeStopPipeline(explicitPromise: promise)
        }
    }

    // Called by the duration-limit timer (main thread). No external promise —
    // result is delivered via the "onRecordingFinished" event.
    private func stopRecordingInternal() {
        stateLock.lock()
        guard currentState == .recording else {
            stateLock.unlock()
            return
        }
        currentState = .stopping
        stateLock.unlock()
        sessionQueue.sync { self.isStopping = true }
        executeStopPipeline(explicitPromise: nil)
    }

    private func executeStopPipeline(explicitPromise: Promise?) {
        recorder.stopCapture { [weak self] error in
            guard let self = self else { return }

            if let error = error {
                explicitPromise?.reject("ERR_STOP_FAILED", error.localizedDescription)
                self.cleanupOnSessionQueue()
                return
            }

            // Dispatch markAsFinished + finishWriting onto sessionQueue so that
            // all in-flight append blocks (queued before isStopping was set) drain
            // first. sessionQueue is serial — ordering is guaranteed.
            self.sessionQueue.async {
                self.videoInput?.markAsFinished()
                self.audioInput?.markAsFinished()

                // FIX 4: Snapshot sessionQueue-owned values into locals NOW,
                // while we are still on sessionQueue and before finishWriting
                // starts. finishWriting's callback runs on an Apple-internal
                // queue; by then, cleanupOnSessionQueue() may have already
                // zeroed these ivars. Using locals guarantees correct values.
                let capturedStartTime = self.startTime
                let capturedOutputURL = self.currentOutputURL

                // FIX 5: Capture assetWriter status and error in the finishWriting
                // callback via a local capture — reading self.assetWriter?.status
                // inside finishWriting's callback is a queue-ownership violation
                // because finishWriting runs on Apple's internal queue, not
                // sessionQueue. Instead, capture the writer reference directly
                // so we read its status through the same retained object without
                // going through self (which may have been cleaned up).
                let writerRef = self.assetWriter

                writerRef?.finishWriting { [weak self] in
                    guard let self = self else { return }

                    if writerRef?.status == .failed {
                        explicitPromise?.reject(
                            "ERR_WRITE_FAILED",
                            writerRef?.error?.localizedDescription ?? "Unknown write error."
                        )
                        self.cleanupOnSessionQueue()
                        return
                    }

                    let durationSec = Date().timeIntervalSince(capturedStartTime ?? Date())
                    let path = capturedOutputURL?.path ?? ""
                    let payload: [String: Any] = [
                        "uri":      "file://\(path)",
                        "duration": durationSec
                    ]

                    if let promise = explicitPromise {
                        promise.resolve(payload)
                    } else {
                        self.sendEvent("onRecordingFinished", payload)
                    }
                    self.cleanupOnSessionQueue()
                }
            }
        }
    }

    // All sessionQueue-owned state is zeroed here on sessionQueue (its owning
    // queue). stateLock-protected and main-thread state are updated after, once
    // all sessionQueue work is complete.
    // The sessionQueue.async wrapper serialises concurrent cleanup callers
    // (e.g. deinit racing with finishWriting callback).
    private func cleanupOnSessionQueue() {
        sessionQueue.async {
            self.assetWriter         = nil
            self.videoInput          = nil
            self.audioInput          = nil
            self.isWritingStarted    = false
            self.writerConfigured    = false
            self.isStopping          = false
            self.includeAudioCapture = false
            self.startTime           = nil
            self.currentOutputURL    = nil
            self.pendingBitRate      = 8_000_000

            DispatchQueue.main.async {
                self.durationTimer?.invalidate()
                self.durationTimer = nil
            }

            self.stateLock.lock()
            self.pendingStopPromise = nil
            self.currentState = .idle
            self.stateLock.unlock()
        }
    }
}

struct RecordingOptions: Record {
    @Field var durationLimit: Double?
    @Field var quality: String?
    @Field var includeAudio: Bool?
}