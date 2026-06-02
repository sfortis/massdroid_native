#include "sendspin_output_engine.h"

#include <android/log.h>
#include <ctime>
#include <cstring>
#include <algorithm>

#define LOG_TAG "SendspinNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sendspin {

namespace {
// Depth of the decoded-PCM ring. Must comfortably exceed the worst JVM GC
// pause (tens of ms) plus the producer's decode burstiness. 4 s is generous
// and cheap (48 kHz stereo i16 = ~768 KB; 96 kHz = ~1.5 MB).
constexpr int RING_SECONDS = 4;

// Below this |drift| the rate stays exactly 1.0 (locked).
constexpr int64_t STEADY_DEADZONE_US = 4000;

// At/above this |drift| we SNAP onto the server timeline immediately (integer
// skip/insert) instead of crawling there — "respect the timestamp", like a
// Cast receiver. A snap on a seek/relock is muted; an occasional unmuted snap
// is one click, far better than seconds of audible desync.
constexpr int64_t SNAP_US = 50000;

// Between the deadzone and the snap threshold, converge by RESAMPLING (a small
// playback-rate change with linear interpolation, sendspin-js style) — smooth
// and click-free. Cap 3% (~30 ms/s); reached at the snap threshold.
constexpr double MAX_RATE_DEV = 0.03;
constexpr double RATE_K = MAX_RATE_DEV / static_cast<double>(SNAP_US);

// A raw drift sample this far from the smoothed value is treated as a
// getTimestamp outlier (bad DAC timestamp): it barely moves the estimate and
// never drives a correction. Without this, a transient 50-300 ms spike makes
// the callback chase a phantom -> audible artifact mid playback even though the
// buffer is healthy (observed on slower devices).
constexpr int64_t OUTLIER_US = 15000;

// Max the timestamp anchor may move per ~100 ms poll. Lets dac0 follow the slow
// real ppm clock drift smoothly while clamping a noisy getTimestamp reading to
// a sub-ms blip (no freeze-then-jump sawtooth that makes the drift wander).
constexpr int64_t MAX_SLEW_US = 1000;

// Gain-ramp time: mute/unmute/volume changes reach the target over this long
// instead of jumping mid-waveform (which clicks).
constexpr float FADE_SEC = 0.02f;
} // namespace

SendspinOutputEngine::~SendspinOutputEngine() {
    stop();
}

int64_t SendspinOutputEngine::monotonicNowUs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

bool SendspinOutputEngine::start(int32_t sampleRate, int32_t channels, bool driftCorrection) {
    stop();
    sampleRate_ = sampleRate;
    channels_ = channels;
    driftCorrection_.store(driftCorrection);
    capacityFrames_ = sampleRate * RING_SECONDS;
    ring_ = std::make_unique<int16_t[]>(static_cast<size_t>(capacityFrames_) * channels_);
    writeIndex_.store(0);
    readIndex_.store(0);
    readPos_ = 0.0;
    markerWrite_.store(0);
    markerRead_.store(0);
    anchorFramePosition_.store(-1);
    anchorTimeUs_.store(0);
    latencyUs_.store(0);
    lastTimestampPollFrame_ = 0;
    driftEmaUs_.store(0);
    underrunFrames_.store(0);
    callbackCount_ = 0;
    postFlushCallbacks_ = 0;
    appliedVolume_ = 0.0f;
    flushRequested_.store(false);

    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setSampleRate(sampleRate)
        ->setChannelCount(channels)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK || !stream_) {
        LOGE("openStream failed: %s", oboe::convertToText(r));
        return false;
    }
    // Two bursts of buffering keeps the fast path while giving the HAL a touch
    // of slack; the deep ring (above) is what actually absorbs GC, not this.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    r = stream_->requestStart();
    if (r != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(r));
        stream_->close();
        stream_.reset();
        return false;
    }
    LOGD("started %dHz ch=%d burst=%d ringFrames=%d", sampleRate, channels,
         stream_->getFramesPerBurst(), capacityFrames_);
    return true;
}

void SendspinOutputEngine::stop() {
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
    ring_.reset();
    capacityFrames_ = 0;
}

void SendspinOutputEngine::flush() {
    // The callback performs the actual reset (it owns readIndex_/markerRead_);
    // write() drops frames while this flag is set so the producer cannot race
    // the reset. See resetRing().
    flushRequested_.store(true);
}

void SendspinOutputEngine::resetRing() {
    const int64_t w = writeIndex_.load();
    readIndex_.store(w);
    readPos_ = static_cast<double>(w);
    markerRead_.store(markerWrite_.load());
    driftEmaUs_.store(0);
}

int32_t SendspinOutputEngine::write(const int16_t* pcm, int32_t frames, int64_t presentationLocalUs) {
    if (frames <= 0 || !ring_ || flushRequested_.load()) return 0;
    int64_t w = writeIndex_.load(std::memory_order_relaxed);
    int64_t r = readIndex_.load(std::memory_order_acquire);
    int64_t freeFrames = static_cast<int64_t>(capacityFrames_) - (w - r);
    if (freeFrames <= 0) return 0;
    int32_t toWrite = static_cast<int32_t>(std::min<int64_t>(frames, freeFrames));

    // Timeline marker for the first frame of this segment.
    int64_t mw = markerWrite_.load(std::memory_order_relaxed);
    int64_t mr = markerRead_.load(std::memory_order_acquire);
    if (mw - mr < MARKER_CAP) {
        markers_[mw % MARKER_CAP] = Marker{w, presentationLocalUs};
        markerWrite_.store(mw + 1, std::memory_order_release);
    }

    // Copy interleaved samples into the ring (up to two segments for wrap).
    int64_t startFrame = w % capacityFrames_;
    int32_t first = static_cast<int32_t>(std::min<int64_t>(toWrite, capacityFrames_ - startFrame));
    std::memcpy(&ring_[startFrame * channels_], pcm,
                static_cast<size_t>(first) * channels_ * sizeof(int16_t));
    if (toWrite > first) {
        std::memcpy(&ring_[0], pcm + static_cast<size_t>(first) * channels_,
                    static_cast<size_t>(toWrite - first) * channels_ * sizeof(int16_t));
    }
    writeIndex_.store(w + toWrite, std::memory_order_release);
    return toWrite;
}

bool SendspinOutputEngine::intendedPresentationUs(int64_t frameIndex, int64_t* outUs) {
    int64_t w = markerWrite_.load(std::memory_order_acquire);
    int64_t r = markerRead_.load(std::memory_order_relaxed);
    bool found = false;
    Marker chosen{};
    int64_t chosenIdx = r;
    for (int64_t i = r; i < w; ++i) {
        Marker m = markers_[i % MARKER_CAP];
        if (m.frameIndex <= frameIndex) {
            chosen = m;
            chosenIdx = i;
            found = true;
        } else {
            break;
        }
    }
    if (!found) return false;
    // Reclaim markers strictly before the chosen one (later frames in the same
    // segment still interpolate from it).
    markerRead_.store(chosenIdx, std::memory_order_relaxed);
    int64_t delta = frameIndex - chosen.frameIndex;
    *outUs = chosen.presentationUs + delta * 1000000LL / sampleRate_;
    return true;
}

void SendspinOutputEngine::refreshTimestampAnchor(oboe::AudioStream* stream, int64_t framesWritten) {
    // Poll roughly every 100 ms of output; cheap and keeps the anchor honest
    // without hammering getTimestamp on every callback.
    if (anchorFramePosition_.load() >= 0 &&
        framesWritten - lastTimestampPollFrame_ < sampleRate_ / 10) {
        return;
    }
    lastTimestampPollFrame_ = framesWritten;
    auto result = stream->getTimestamp(CLOCK_MONOTONIC);
    if (!result) return;
    auto ts = result.value();
    const int64_t newPos = ts.position;
    const int64_t newTimeUs = ts.timestamp / 1000;
    const int64_t nowUs = monotonicNowUs();

    // Basic sanity: a real output timestamp marks a frame already/about to be
    // presented, so its position is within what we've written and its time is
    // near (not wildly in the future/past).
    if (newPos <= 0 || newPos > framesWritten) return;
    if (newTimeUs > nowUs + 50000 || newTimeUs < nowUs - 1000000) return;

    const int64_t oldPos = anchorFramePosition_.load();
    if (oldPos < 0) {
        // First anchor: accept.
        anchorFramePosition_.store(newPos);
        anchorTimeUs_.store(newTimeUs);
    } else {
        // SLEW-LIMITED tracking. getTimestamp is noisy on some HALs; a hard
        // accept/reject scheme either freezes the anchor (then jumps when it
        // finally re-seats, a sawtooth that makes the drift wander 5-37 ms and
        // never lock) or chases spikes. Instead, move the anchor toward each
        // reading by at most MAX_SLEW_US per poll: a genuine slow ppm clock
        // drift is followed smoothly, while a one-off spike is clamped to a
        // sub-ms blip and recovered on the next poll. dac0 stays continuous.
        const int64_t predictedTimeUs = anchorTimeUs_.load() +
            (newPos - oldPos) * 1000000LL / sampleRate_;
        int64_t deltaUs = newTimeUs - predictedTimeUs;
        if (deltaUs > MAX_SLEW_US) deltaUs = MAX_SLEW_US;
        else if (deltaUs < -MAX_SLEW_US) deltaUs = -MAX_SLEW_US;
        anchorFramePosition_.store(newPos);
        anchorTimeUs_.store(predictedTimeUs + deltaUs);
    }
    const int64_t inflight = framesWritten - newPos;
    if (inflight >= 0) {
        latencyUs_.store(inflight * 1000000LL / sampleRate_);
    }
}

int64_t SendspinOutputEngine::dacPresentationUsForNextWrite(int64_t framesWritten) const {
    int64_t pos = anchorFramePosition_.load();
    if (pos < 0) {
        // No timestamp yet: presentation of the next frame ~= now + buffer lag.
        return monotonicNowUs() + latencyUs_.load();
    }
    int64_t t = anchorTimeUs_.load();
    return t + (framesWritten - pos) * 1000000LL / sampleRate_;
}

int64_t SendspinOutputEngine::outputLatencyUs() const {
    return latencyUs_.load();
}

int64_t SendspinOutputEngine::bufferedFrames() const {
    return writeIndex_.load(std::memory_order_acquire) - readIndex_.load(std::memory_order_relaxed);
}

int32_t SendspinOutputEngine::deviceId() const {
    return stream_ ? stream_->getDeviceId() : 0;
}

oboe::DataCallbackResult SendspinOutputEngine::onAudioReady(
    oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    callbackCount_++;
    auto* out = static_cast<int16_t*>(audioData);
    const int ch = channels_;

    if (flushRequested_.load()) {
        int64_t dropped = writeIndex_.load() - readIndex_.load();
        resetRing();
        flushRequested_.store(false);
        postFlushCallbacks_ = 32;
        LOGD("flush ack: dropped %lld buffered frames", (long long)dropped);
    }

    // Advance the gain toward the target a little (fade); applied per-sample
    // below so mute/unmute/volume never jumps mid-waveform.
    const float target = volume_.load();
    const float g0 = appliedVolume_;
    const float maxStep = static_cast<float>(numFrames) / (static_cast<float>(sampleRate_) * FADE_SEC);
    float g1 = g0;
    if (target > g0) g1 = std::min(target, g0 + maxStep);
    else if (target < g0) g1 = std::max(target, g0 - maxStep);
    appliedVolume_ = g1;

    int64_t framesWritten = stream->getFramesWritten();
    refreshTimestampAnchor(stream, framesWritten);

    int64_t read = static_cast<int64_t>(readPos_);
    int64_t write = writeIndex_.load(std::memory_order_acquire);
    int64_t available = write - read;

    if (available <= 0) {
        std::memset(out, 0, static_cast<size_t>(numFrames) * ch * sizeof(int16_t));
        if (postFlushCallbacks_ > 0) {
            postFlushCallbacks_--;
            LOGD("post-flush cb: SILENCE (ring empty) g=%.2f->%.2f", g0, g1);
        }
        return oboe::DataCallbackResult::Continue;
    }

    // When will out[0] leave the DAC, and when was the ring head meant to play?
    int64_t dac0 = dacPresentationUsForNextWrite(framesWritten);
    int64_t intendedHead;
    if (!intendedPresentationUs(read, &intendedHead)) {
        intendedHead = dac0; // no marker covers it yet: play as-is
    }
    const int64_t rawDriftUs = intendedHead - dac0; // >0 early (insert), <0 late (skip)

    // Outlier-resistant smoothed drift. A bad getTimestamp reading spikes the
    // raw drift for a callback or two; correcting against that raw spike would
    // skip/insert a big chunk of samples -> audible "cough". Correct against
    // this estimate instead: it barely moves on single-callback outliers and
    // only tracks SUSTAINED drift (genuine boundaries flush + relock anyway).
    const int64_t prevEma = driftEmaUs_.load();
    int64_t ema;
    if (callbackCount_ < 4) {
        ema = rawDriftUs;
    } else {
        int64_t dev = rawDriftUs - prevEma;
        if (dev < 0) dev = -dev;
        ema = (dev > OUTLIER_US) ? prevEma + (rawDriftUs - prevEma) / 64
                                 : prevEma + (rawDriftUs - prevEma) / 8;
    }
    driftEmaUs_.store(ema);

    const int64_t driftUs = ema;
    int silenceFront = 0;
    double pos = readPos_;
    double rate = 1.0;

    // --- Drift correction (SYNC only) ------------------------------------
    // Solo/DIRECT leaves driftCorrection off -> rate stays 1.0 (exact
    // passthrough). Grouped/SYNC aligns to the absolute group timeline.
    if (driftCorrection_.load()) {
        const int64_t adrift = driftUs < 0 ? -driftUs : driftUs;
        // While the output is silent (g≈0: startup, seek, resume — the engine
        // mutes across these and only unmutes once locked), SNAP onto the
        // timeline immediately by skip/insert. It is inaudible while muted and
        // locks in ~1 callback, so audio resumes the instant the mute lifts
        // instead of after seconds of muted resampler crawl. A huge audible
        // drift (>= SNAP_US, rare) also snaps: one click beats a long desync.
        const bool silent = (g1 < 0.01f && g0 < 0.01f);
        if ((silent || adrift >= SNAP_US) && adrift > STEADY_DEADZONE_US) {
            const int64_t driftFrames = driftUs * sampleRate_ / 1000000LL;
            if (driftUs > 0) {
                silenceFront = static_cast<int>(std::min<int64_t>(numFrames, driftFrames));
                std::memset(out, 0, static_cast<size_t>(silenceFront) * ch * sizeof(int16_t));
            } else {
                int64_t skip = std::min<int64_t>(available - 2, -driftFrames);
                if (skip > 0) pos += static_cast<double>(skip);
            }
        } else if (adrift > STEADY_DEADZONE_US) {
            // Audible residual: RESAMPLE at a hair off 1.0 — smooth, click-free
            // (sendspin-js style). drift>0 (early) -> slower (<1); <0 -> faster.
            double dev = static_cast<double>(driftUs) * RATE_K;
            if (dev > MAX_RATE_DEV) dev = MAX_RATE_DEV;
            else if (dev < -MAX_RATE_DEV) dev = -MAX_RATE_DEV;
            rate = 1.0 - dev;
        }
    }

    // --- Resampled fill with gain fade ----------------------------------
    // Linear interpolation between ring[i0] and ring[i0+1] at the fractional
    // position; advancing pos by `rate` per output frame is the resampler.
    const float span = static_cast<float>(numFrames);
    int produced = 0;
    for (int f = 0; f < numFrames - silenceFront; ++f) {
        const int64_t i0 = static_cast<int64_t>(pos);
        if (i0 + 1 >= write) break; // need i0 and i0+1 buffered, else underrun
        const float frac = static_cast<float>(pos - static_cast<double>(i0));
        const int64_t s0 = (i0 % capacityFrames_) * ch;
        const int64_t s1 = ((i0 + 1) % capacityFrames_) * ch;
        const float gain = g0 + (g1 - g0) * static_cast<float>(silenceFront + f) / span;
        int16_t* dp = out + static_cast<size_t>(silenceFront + f) * ch;
        for (int c = 0; c < ch; ++c) {
            const float a = static_cast<float>(ring_[s0 + c]);
            const float b = static_cast<float>(ring_[s1 + c]);
            dp[c] = static_cast<int16_t>((a + (b - a) * frac) * gain);
        }
        pos += rate;
        ++produced;
    }
    readPos_ = pos;
    readIndex_.store(static_cast<int64_t>(pos), std::memory_order_release);

    int framesEmitted = silenceFront + produced;
    // Pad any shortfall with silence (ring underrun within this callback).
    if (framesEmitted < numFrames) {
        std::memset(out + static_cast<size_t>(framesEmitted) * ch, 0,
                    static_cast<size_t>(numFrames - framesEmitted) * ch * sizeof(int16_t));
        underrunFrames_.fetch_add(numFrames - framesEmitted);
    }

    if (postFlushCallbacks_ > 0) {
        postFlushCallbacks_--;
        LOGD("post-flush cb: produced=%d emitted=%d drift=%lldus rate=%.5f g=%.2f->%.2f avail=%lld",
             produced, framesEmitted, (long long)driftUs, rate, g0, g1, (long long)available);
    }

    if ((callbackCount_ & 0x1FF) == 0) {
        auto cl = stream->calculateLatencyMillis();
        double calcLatMs = cl ? cl.value() : -1.0;
        LOGD("cb#%lld raw=%lldus ema=%lldus rate=%.5f buf=%lldms lat(gts)=%lldms calcLat=%.1fms underrun=%lld",
             (long long)callbackCount_, (long long)rawDriftUs, (long long)driftUs, rate,
             (long long)(bufferedFrames() * 1000 / sampleRate_), (long long)(latencyUs_.load() / 1000),
             calcLatMs, (long long)underrunFrames_.load());
    }
    return oboe::DataCallbackResult::Continue;
}

void SendspinOutputEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // Disconnects (e.g. BT route change) close the stream. The Kotlin engine
    // owns route-change handling and will reconfigure/restart us; just log.
    LOGW("stream error after close: %s", oboe::convertToText(error));
}

} // namespace sendspin
