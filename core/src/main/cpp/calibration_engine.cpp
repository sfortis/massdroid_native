#include "calibration_engine.h"

#include <android/log.h>
#include <chrono>
#include <cstring>
#include <thread>

#define LOG_TAG "AcousticCal"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace acoustic {

CalibrationEngine::CalibrationEngine() = default;
CalibrationEngine::~CalibrationEngine() { closeStreams(); }

bool CalibrationEngine::openStreams(int outputDeviceId) {
    // Output stream: speaker playback. Optional outputDeviceId pins the
    // route to a specific AAudio device (e.g. TYPE_BUILTIN_SPEAKER) so the
    // mic_path reference pass can run on the phone speaker even while BT
    // is the system default output. Passing 0 (kUnspecified) lets the
    // framework pick the default route.
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
        ->setSampleRate(SAMPLE_RATE)
        ->setChannelCount(1)
        ->setFormat(oboe::AudioFormat::I16)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (outputDeviceId != 0) {
        outBuilder.setDeviceId(outputDeviceId);
    }

    oboe::Result result = outBuilder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        return false;
    }
    routedOutputDeviceId_ = outputStream_->getDeviceId();
    LOGD("Output stream opened: sampleRate=%d sharingMode=%s perfMode=%s "
         "requestedDeviceId=%d routedDeviceId=%d",
         outputStream_->getSampleRate(),
         oboe::convertToText(outputStream_->getSharingMode()),
         oboe::convertToText(outputStream_->getPerformanceMode()),
         outputDeviceId,
         routedOutputDeviceId_);

    // Input stream: microphone recording.
    //
    // InputPreset::Unprocessed disables platform DSP (noise suppression, AEC,
    // automatic gain) on the mic chain. This matters for the calibration
    // chirp because (a) we want clean envelope detection of the 1kHz tones
    // and (b) Oboe's calculateLatencyMillis() reports the AAudio timestamp
    // model's view of the input HAL only — when extra DSP stages sit between
    // the mic and the buffer (VoicePerformance enables them), those stages
    // add latency that the reported value under-counts. Switching to
    // Unprocessed brings the reported latency closer to the real one-way
    // mic path, which is what we subtract from the round trip.
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setSampleRate(SAMPLE_RATE)
        ->setChannelCount(1)
        ->setFormat(oboe::AudioFormat::I16)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    result = inBuilder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        outputStream_->close();
        outputStream_.reset();
        return false;
    }
    routedInputDeviceId_ = inputStream_->getDeviceId();
    LOGD("Input stream opened: sampleRate=%d sharingMode=%s inputPreset=%s routedDeviceId=%d",
         inputStream_->getSampleRate(),
         oboe::convertToText(inputStream_->getSharingMode()),
         oboe::convertToText(inputStream_->getInputPreset()),
         routedInputDeviceId_);

    return true;
}

void CalibrationEngine::closeStreams() {
    if (outputStream_) {
        outputStream_->stop();
        outputStream_->close();
        outputStream_.reset();
    }
    if (inputStream_) {
        inputStream_->stop();
        inputStream_->close();
        inputStream_.reset();
    }
}

CalibrationResult CalibrationEngine::measureRoundTrip(
    int maxDelayMs, int outputDeviceId, ProgressCallback progress
) {
    progressCallback_ = std::move(progress);

    // Generate tone sequence
    toneSeq_ = generateToneSequence();
    toneSamples_ = msToSamples(TONE_DURATION_MS);

    // Allocate recording buffer: playback duration + 500ms safety margin
    int extraSamples = SAMPLE_RATE / 2;
    recordBuffer_.resize(toneSeq_.totalSamples + extraSamples, 0);

    // Reset state
    playPos_.store(0);
    recordPos_.store(0);
    recordPosAtPlayStart_.store(-1);
    outputStarted_.store(false);
    playbackDone_.store(false);
    recordingDone_.store(false);
    errorOccurred_.store(false);
    lastReportedTone_.store(0);
    capturedInputLatencyUs_.store(0);
    latencyQueryAttempts_.store(0);
    outputAnchorFramePosition_.store(-1);
    outputAnchorTimeNanos_.store(0);
    outputStartRequestTimeNanos_.store(0);
    outputTimestampQueryAttempts_.store(0);
    routedOutputDeviceId_ = 0;
    routedInputDeviceId_ = 0;

    // Open Oboe streams
    if (!openStreams(outputDeviceId)) {
        return CalibrationResult{0, 0, 0.0, 0.0f, 2}; // FAILED
    }

    // Start input stream first (recording must be running before playback)
    oboe::Result result = inputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        closeStreams();
        return CalibrationResult{0, 0, 0.0, 0.0f, 2};
    }
    LOGD("Input stream started, waiting for pre-roll...");

    // Wait for completion (output is started from the input callback after pre-roll)
    {
        std::unique_lock<std::mutex> lock(completionMutex_);
        bool completed = completionCv_.wait_for(lock, std::chrono::seconds(15), [this] {
            return (playbackDone_.load() && recordingDone_.load()) || errorOccurred_.load();
        });

        if (!completed) {
            LOGW("Calibration timed out");
            closeStreams();
            return CalibrationResult{0, 0, 0.0, 0.0f, 2};
        }
    }

    if (errorOccurred_.load()) {
        LOGE("Stream error during calibration");
        closeStreams();
        return CalibrationResult{0, 0, 0.0, 0.0f, 2, 0};
    }

    // Pull the input-path latency captured by the input callback while the
    // stream was still RUNNING. Calling calculateLatencyMillis() here, after
    // the data callback returned Stop, would yield ErrorInvalidState — the
    // AAudio timestamp model requires a running stream.
    int64_t inputLatencyUs = capturedInputLatencyUs_.load(std::memory_order_acquire);
    if (inputLatencyUs > 0) {
        LOGD("Input latency captured from running stream: %lld us (%.2f ms)",
             static_cast<long long>(inputLatencyUs), inputLatencyUs / 1000.0);
    } else {
        LOGW("Input latency not captured (callback attempts exhausted or value <= 0)");
    }

    // Extrapolate output HAL from the captured Oboe timestamp anchor.
    // frameZeroAtDacNanos = anchorTimeNanos - (anchorFramePosition * 1e9 /
    // sampleRate); outputHAL = frameZeroAtDacNanos - requestStartNanos.
    // Bounded to a reasonable range (1-500 ms) so a glitched anchor doesn't
    // poison the result; outside that range we treat it as not captured.
    int64_t outputHALUs = 0;
    int64_t anchorPos = outputAnchorFramePosition_.load(std::memory_order_acquire);
    int64_t anchorTimeNanos = outputAnchorTimeNanos_.load(std::memory_order_acquire);
    int64_t requestTimeNanos = outputStartRequestTimeNanos_.load(std::memory_order_acquire);
    if (anchorPos > 0 && anchorTimeNanos > 0 && requestTimeNanos > 0) {
        int64_t frameZeroAtDacNanos =
            anchorTimeNanos - (anchorPos * 1'000'000'000LL / SAMPLE_RATE);
        int64_t hal = (frameZeroAtDacNanos - requestTimeNanos) / 1000LL;
        if (hal >= 1'000 && hal <= 500'000) {
            outputHALUs = hal;
            LOGD("Output HAL extrapolated: %lld us (%.2f ms) "
                 "(anchorPos=%lld anchorTimeNs=%lld requestTimeNs=%lld)",
                 static_cast<long long>(outputHALUs), outputHALUs / 1000.0,
                 static_cast<long long>(anchorPos),
                 static_cast<long long>(anchorTimeNanos),
                 static_cast<long long>(requestTimeNanos));
        } else {
            LOGW("Output HAL out of range (%lld us); ignoring",
                 static_cast<long long>(hal));
        }
    } else {
        LOGW("Output HAL not captured (anchorPos=%lld anchorTimeNs=%lld requestTimeNs=%lld)",
             static_cast<long long>(anchorPos),
             static_cast<long long>(anchorTimeNanos),
             static_cast<long long>(requestTimeNanos));
    }

    int32_t routedOutId = routedOutputDeviceId_;
    int32_t routedInId  = routedInputDeviceId_;

    closeStreams();

    int capturedSamples = recordPos_.load();
    int syncAnchor = recordPosAtPlayStart_.load();
    LOGD("Recording complete: %d samples, syncAnchor=%d", capturedSamples, syncAnchor);

    if (syncAnchor < 0) {
        LOGE("Sync anchor never set (output never started)");
        return CalibrationResult{0, 0, 0.0, 0.0f, 2, inputLatencyUs};
    }

    // DSP: bandpass filter -> envelope -> analysis
    auto filtered = bandpassFilter(recordBuffer_.data(), capturedSamples);
    auto envelope = computeEnvelope(filtered.data(), capturedSamples);

    auto calibrationResult = analyzeRecording(
        envelope,
        toneSeq_.toneOffsets,
        syncAnchor,
        toneSeq_.preRollSamples,
        maxDelayMs,
        SAMPLE_RATE
    );
    calibrationResult.inputLatencyUs = inputLatencyUs;
    calibrationResult.outputHALUs = outputHALUs;
    calibrationResult.routedOutputDeviceId = routedOutId;
    calibrationResult.routedInputDeviceId = routedInId;
    return calibrationResult;
}

oboe::DataCallbackResult CalibrationEngine::onAudioReady(
    oboe::AudioStream* stream, void* audioData, int32_t numFrames
) {
    if (stream == inputStream_.get()) {
        // --- INPUT (recording) callback ---
        auto* inData = static_cast<const int16_t*>(audioData);
        int32_t pos = recordPos_.load(std::memory_order_relaxed);
        int32_t remaining = static_cast<int32_t>(recordBuffer_.size()) - pos;
        int32_t toCopy = std::min(numFrames, remaining);

        if (toCopy > 0) {
            std::memcpy(recordBuffer_.data() + pos, inData, toCopy * sizeof(int16_t));
            recordPos_.store(pos + toCopy, std::memory_order_release);
        }

        // Capture the input-path latency while the stream is still RUNNING.
        // AAudio's getTimestamp() (and therefore calculateLatencyMillis)
        // returns ErrorInvalidState once the data callback returns Stop, so
        // querying in measureRoundTrip after wait_for completes does not
        // work. We retry from a few callbacks (the timestamp model needs a
        // little warm-up after stream start) and stop once we get a value
        // or exhaust MAX_LATENCY_QUERY_ATTEMPTS. The Oboe API is lock-free
        // and safe to call from the audio callback.
        if (outputStarted_.load(std::memory_order_acquire)
            && capturedInputLatencyUs_.load(std::memory_order_relaxed) == 0) {
            int attempt = latencyQueryAttempts_.fetch_add(1, std::memory_order_relaxed);
            if (attempt < MAX_LATENCY_QUERY_ATTEMPTS) {
                auto lat = stream->calculateLatencyMillis();
                if (lat) {
                    int64_t latencyUs = static_cast<int64_t>(lat.value() * 1000.0);
                    if (latencyUs > 0) {
                        capturedInputLatencyUs_.store(latencyUs, std::memory_order_release);
                    }
                } else if (attempt == MAX_LATENCY_QUERY_ATTEMPTS - 1) {
                    LOGW("Input latency unavailable after %d attempts: %s",
                         MAX_LATENCY_QUERY_ATTEMPTS, oboe::convertToText(lat.error()));
                }
            }
        }

        // Capture an Oboe output-stream timestamp anchor (position, time)
        // while the OUTPUT is RUNNING. Extrapolated post-recording into
        // outputHALUs = time-at-which-frame-0-left-DAC minus
        // outputStartRequestTimeNanos. For in-phone output (built-in
        // speaker, wired, USB) this is the true output pipeline latency
        // that we subtract along with mic_path. For BT it reflects only
        // the time frames left the host SDK boundary (not the BT speaker
        // DAC), so callers must interpret it as "active output emission
        // timestamp" rather than "BT pipeline latency".
        if (outputStarted_.load(std::memory_order_acquire)
            && outputStream_
            && outputAnchorFramePosition_.load(std::memory_order_relaxed) < 0) {
            int attempt = outputTimestampQueryAttempts_.fetch_add(1, std::memory_order_relaxed);
            if (attempt < MAX_OUTPUT_TIMESTAMP_ATTEMPTS) {
                auto ts = outputStream_->getTimestamp(CLOCK_MONOTONIC);
                if (ts) {
                    auto frame = ts.value();
                    if (frame.position > 0) {
                        outputAnchorFramePosition_.store(frame.position, std::memory_order_relaxed);
                        outputAnchorTimeNanos_.store(frame.timestamp, std::memory_order_release);
                    }
                } else if (attempt == MAX_OUTPUT_TIMESTAMP_ATTEMPTS - 1) {
                    LOGW("Output getTimestamp unavailable after %d attempts: %s",
                         MAX_OUTPUT_TIMESTAMP_ATTEMPTS, oboe::convertToText(ts.error()));
                }
            }
        }

        // Start output after pre-roll is captured
        if (!outputStarted_.load(std::memory_order_acquire)
            && recordPos_.load(std::memory_order_acquire) >= toneSeq_.preRollSamples) {

            recordPosAtPlayStart_.store(
                recordPos_.load(std::memory_order_acquire),
                std::memory_order_release
            );

            if (outputStream_) {
                // Stamp world time the request was issued so we can later
                // extrapolate output HAL = frame0_at_DAC_time - request_time.
                struct timespec ts;
                clock_gettime(CLOCK_MONOTONIC, &ts);
                int64_t requestTimeNanos = static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL
                                         + static_cast<int64_t>(ts.tv_nsec);
                outputStartRequestTimeNanos_.store(requestTimeNanos, std::memory_order_release);

                oboe::Result r = outputStream_->requestStart();
                if (r != oboe::Result::OK) {
                    LOGE("Failed to start output from input callback: %s",
                         oboe::convertToText(r));
                    errorOccurred_.store(true);
                    completionCv_.notify_all();
                    return oboe::DataCallbackResult::Stop;
                }
                LOGD("Output started at recordPos=%d", recordPosAtPlayStart_.load());
            }
            outputStarted_.store(true, std::memory_order_release);
        }

        // Stop recording after playback is done + tail captured
        if (playbackDone_.load(std::memory_order_acquire)) {
            int tailSamples = msToSamples(TAIL_MS);
            int playEndInRec = recordPosAtPlayStart_.load() + toneSeq_.totalSamples;
            if (pos >= playEndInRec + tailSamples || remaining <= 0) {
                recordingDone_.store(true, std::memory_order_release);
                completionCv_.notify_all();
                return oboe::DataCallbackResult::Stop;
            }
        }

        if (remaining <= 0) {
            recordingDone_.store(true, std::memory_order_release);
            completionCv_.notify_all();
            return oboe::DataCallbackResult::Stop;
        }

        return oboe::DataCallbackResult::Continue;

    } else if (stream == outputStream_.get()) {
        // --- OUTPUT (playback) callback ---
        auto* outData = static_cast<int16_t*>(audioData);
        int32_t pos = playPos_.load(std::memory_order_relaxed);
        int32_t bufSize = static_cast<int32_t>(toneSeq_.buffer.size());
        int32_t remaining = bufSize - pos;
        int32_t toCopy = std::min(numFrames, remaining);

        if (toCopy > 0) {
            std::memcpy(outData, toneSeq_.buffer.data() + pos, toCopy * sizeof(int16_t));
            playPos_.store(pos + toCopy, std::memory_order_release);
            updateProgress(pos + toCopy);
        }

        // Zero-fill if buffer exhausted
        if (toCopy < numFrames) {
            std::memset(outData + toCopy, 0, (numFrames - toCopy) * sizeof(int16_t));
            playbackDone_.store(true, std::memory_order_release);
            return oboe::DataCallbackResult::Stop;
        }

        return oboe::DataCallbackResult::Continue;
    }

    return oboe::DataCallbackResult::Continue;
}

void CalibrationEngine::onErrorBeforeClose(
    oboe::AudioStream* stream, oboe::Result error
) {
    LOGE("Stream error: %s (stream=%s)",
         oboe::convertToText(error),
         stream == outputStream_.get() ? "output" : "input");
    errorOccurred_.store(true);
    completionCv_.notify_all();
}

void CalibrationEngine::updateProgress(int32_t playPos) {
    for (int i = TONE_COUNT - 1; i >= 0; --i) {
        if (playPos >= toneSeq_.toneOffsets[i] + toneSamples_) {
            int toneNum = i + 1;
            int prev = lastReportedTone_.load(std::memory_order_relaxed);
            if (toneNum > prev) {
                lastReportedTone_.store(toneNum, std::memory_order_relaxed);
                if (progressCallback_) {
                    progressCallback_(toneNum, TONE_COUNT);
                }
            }
            break;
        }
    }
}

} // namespace acoustic
