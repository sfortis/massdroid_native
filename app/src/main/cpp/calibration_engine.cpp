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

bool CalibrationEngine::openStreams() {
    // Output stream: speaker playback
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

    oboe::Result result = outBuilder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        return false;
    }
    LOGD("Output stream opened: sampleRate=%d sharingMode=%s perfMode=%s",
         outputStream_->getSampleRate(),
         oboe::convertToText(outputStream_->getSharingMode()),
         oboe::convertToText(outputStream_->getPerformanceMode()));

    // Input stream: microphone recording
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setSampleRate(SAMPLE_RATE)
        ->setChannelCount(1)
        ->setFormat(oboe::AudioFormat::I16)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setInputPreset(oboe::InputPreset::VoicePerformance)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    result = inBuilder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        outputStream_->close();
        outputStream_.reset();
        return false;
    }
    LOGD("Input stream opened: sampleRate=%d sharingMode=%s inputPreset=%s",
         inputStream_->getSampleRate(),
         oboe::convertToText(inputStream_->getSharingMode()),
         oboe::convertToText(inputStream_->getInputPreset()));

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
    int maxDelayMs, ProgressCallback progress
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

    // Open Oboe streams
    if (!openStreams()) {
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
        return CalibrationResult{0, 0, 0.0, 0.0f, 2};
    }

    closeStreams();

    int capturedSamples = recordPos_.load();
    int syncAnchor = recordPosAtPlayStart_.load();
    LOGD("Recording complete: %d samples, syncAnchor=%d", capturedSamples, syncAnchor);

    if (syncAnchor < 0) {
        LOGE("Sync anchor never set (output never started)");
        return CalibrationResult{0, 0, 0.0, 0.0f, 2};
    }

    // DSP: bandpass filter -> envelope -> analysis
    auto filtered = bandpassFilter(recordBuffer_.data(), capturedSamples);
    auto envelope = computeEnvelope(filtered.data(), capturedSamples);

    return analyzeRecording(
        envelope,
        toneSeq_.toneOffsets,
        syncAnchor,
        toneSeq_.preRollSamples,
        maxDelayMs,
        SAMPLE_RATE
    );
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

        // Start output after pre-roll is captured
        if (!outputStarted_.load(std::memory_order_acquire)
            && recordPos_.load(std::memory_order_acquire) >= toneSeq_.preRollSamples) {

            recordPosAtPlayStart_.store(
                recordPos_.load(std::memory_order_acquire),
                std::memory_order_release
            );

            if (outputStream_) {
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
