#include "calibration_engine.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstring>

#define LOG_TAG "AcousticCal"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace acoustic {

namespace {
constexpr int CHANNELS = 2;
// The first callbacks of a signal are skipped for timestamps: on a fresh
// stream some HALs report none or unstable ones there.
constexpr int TIMESTAMP_WARMUP_CALLBACKS = 8;
// Silence counted into the signal so its last frames are presented before
// playSignal returns; the caller's recording covers the rest.
constexpr int64_t TAIL_FRAMES = SAMPLE_RATE / 2;
constexpr auto PLAY_TIMEOUT = std::chrono::seconds(20);
}

CalibrationEngine::~CalibrationEngine() { close(); }

bool CalibrationEngine::open(int outputDeviceId) {
    close();
    state_.store(BlockState::SILENT);
    error_.store(false);
    // The same configuration as SendspinOutputEngine, so the signal takes the
    // path the music takes, effect stages (Dolby on Samsung) included.
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setSampleRate(SAMPLE_RATE)
        ->setChannelCount(CHANNELS)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (outputDeviceId != 0) builder.setDeviceId(outputDeviceId);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }
    LOGD("Output stream opened: rate=%d sharing=%s perf=%s requestedDevice=%d routedDevice=%d",
         stream_->getSampleRate(),
         oboe::convertToText(stream_->getSharingMode()),
         oboe::convertToText(stream_->getPerformanceMode()),
         outputDeviceId, stream_->getDeviceId());
    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        close();
        return false;
    }
    return true;
}

void CalibrationEngine::close() {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

PlayResult CalibrationEngine::playSignal(const std::vector<int16_t>& signal) {
    PlayResult out;
    if (!stream_ || signal.empty() || error_.load()) return out;
    out.routedOutputDeviceId = stream_->getDeviceId();
    out.sampleRate = stream_->getSampleRate();
    auto xRunsBefore = stream_->getXRunCount();

    signal_ = signal;
    timestampCount_.store(0);
    // The callback takes the signal on its next run; the release pairs with its acquire.
    state_.store(BlockState::PENDING, std::memory_order_release);
    {
        std::unique_lock<std::mutex> lock(mutex_);
        bool finished = cv_.wait_for(lock, PLAY_TIMEOUT, [this] {
            return state_.load() == BlockState::DONE || error_.load();
        });
        if (!finished) LOGW("Playback timed out");
    }
    const bool done = state_.load() == BlockState::DONE;
    state_.store(BlockState::SILENT);

    auto xRunsAfter = stream_->getXRunCount();
    out.xRuns = (xRunsBefore && xRunsAfter) ? xRunsAfter.value() - xRunsBefore.value() : 0;
    if (error_.load() || !done) return out;
    out.frameOffset = frameOffset_;
    out.timestampSamples = std::min(timestampCount_.load(), TIMESTAMP_CAPACITY);
    out.ok = medianFrame0(out.outputFrame0Nanos, out.outputSpreadNanos);
    LOGD("Played %zu frames: frame0=%lld ns spread=%lld ns samples=%d xruns=%d offset=%lld ok=%d",
         signal_.size(), static_cast<long long>(out.outputFrame0Nanos),
         static_cast<long long>(out.outputSpreadNanos), out.timestampSamples, out.xRuns,
         static_cast<long long>(out.frameOffset), out.ok ? 1 : 0);
    return out;
}

oboe::DataCallbackResult CalibrationEngine::onAudioReady(
    oboe::AudioStream* stream, void* audioData, int32_t numFrames
) {
    auto* out = static_cast<int16_t*>(audioData);
    BlockState state = state_.load(std::memory_order_acquire);
    if (state == BlockState::PENDING) {
        // The stream frame this callback's first frame becomes, the same mapping
        // SendspinOutputEngine aligns the music with.
        frameOffset_ = stream->getFramesWritten();
        playPos_ = 0;
        blockCallbacks_ = 0;
        state = BlockState::PLAYING;
        state_.store(state);
    }
    if (state != BlockState::PLAYING) {
        std::memset(out, 0, static_cast<size_t>(numFrames) * CHANNELS * sizeof(int16_t));
        return oboe::DataCallbackResult::Continue;
    }

    const auto total = static_cast<int64_t>(signal_.size());
    for (int32_t i = 0; i < numFrames; ++i) {
        const int64_t pos = playPos_ + i;
        const int16_t sample = pos < total ? signal_[static_cast<size_t>(pos)] : 0;
        for (int c = 0; c < CHANNELS; ++c) out[i * CHANNELS + c] = sample;
    }
    playPos_ += numFrames;

    if (++blockCallbacks_ > TIMESTAMP_WARMUP_CALLBACKS) sampleTimestamp(stream);

    if (playPos_ >= total + TAIL_FRAMES) {
        state_.store(BlockState::DONE);
        cv_.notify_all();
    }
    return oboe::DataCallbackResult::Continue;
}

void CalibrationEngine::sampleTimestamp(oboe::AudioStream* stream) {
    const int count = timestampCount_.load(std::memory_order_relaxed);
    if (count >= TIMESTAMP_CAPACITY) return;
    auto result = stream->getTimestamp(CLOCK_MONOTONIC);
    if (!result) return;
    const auto ts = result.value();
    if (ts.position <= frameOffset_) return;
    const int rate = stream->getSampleRate() > 0 ? stream->getSampleRate() : SAMPLE_RATE;
    frame0Nanos_[count] = ts.timestamp -
        (ts.position - frameOffset_) * 1'000'000'000LL / rate;
    timestampCount_.store(count + 1, std::memory_order_release);
}

bool CalibrationEngine::medianFrame0(int64_t& value, int64_t& spread) const {
    const int count = std::min(timestampCount_.load(), TIMESTAMP_CAPACITY);
    if (count == 0) return false;
    std::vector<int64_t> sorted(frame0Nanos_, frame0Nanos_ + count);
    std::sort(sorted.begin(), sorted.end());
    value = sorted[sorted.size() / 2];
    spread = std::max(value - sorted.front(), sorted.back() - value);
    return true;
}

void CalibrationEngine::onErrorBeforeClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGE("Output stream error: %s", oboe::convertToText(error));
    error_.store(true);
    cv_.notify_all();
}

} // namespace acoustic
