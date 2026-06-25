#include "sendspin_output_engine.h"

#include <android/log.h>
#include <ctime>
#include <cstring>
#include <algorithm>
#include <cmath>

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

// Below this |drift| the rate stays exactly 1.0 (locked). Kept tight (1 ms) so
// steady-state lock lands sub-2ms like the old closed loop; the correction here
// is the gentle resampler (a ~0.06% rate nudge at 1 ms), never the snap, so a
// small deadzone does not reintroduce the getTimestamp-spike "cough" (that is
// gated by the outlier-resistant ema + slew limit on the snap path below).
constexpr int64_t STEADY_DEADZONE_US = 1000;

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

// Max the (calcLat-derived) output latency may move per ~100 ms poll. The true
// HAL latency is ~constant, so slew-clamp it: a one-off calculateLatencyMillis
// spike moves the value <=1 ms (recovered next poll) instead of perturbing the
// timeline and triggering a momentary resampler correction. A genuine latency
// change is rare and re-anchored by a flush/relock anyway.
constexpr int64_t LAT_SLEW_US = 1000;

// Gain-ramp time: mute/unmute/volume changes reach the target over this long
// instead of jumping mid-waveform (which clicks).
constexpr float FADE_SEC = 0.02f;

// Dynamic-range compressor presets (phone-as-speaker). Amplitude-only: applied
// in the output callback alongside the volume gain, so it has NO effect on the
// timeline/ring/latency (sync-safe). Index 0 = off (bypass). A soft-knee-free
// static curve with a peak envelope follower; makeup raises the overall level so
// quiet passages come up. Higher levels = lower threshold + higher ratio + more
// makeup = smaller dynamic range (louder, denser).
struct CompPreset {
    float thresholdDb;
    float ratio;
    float makeupDb;
    float attackMs;
    float releaseMs;
};
constexpr CompPreset kCompPresets[4] = {
    {0.0f, 1.0f, 0.0f, 0.0f, 0.0f},        // 0 off (unused, bypassed)
    {-18.0f, 2.0f, 2.0f, 10.0f, 150.0f},   // 1 soft
    {-24.0f, 3.0f, 4.0f, 8.0f, 150.0f},    // 2 medium
    {-30.0f, 4.0f, 6.0f, 5.0f, 120.0f},    // 3 hard
};
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
    lastRateMicros_.store(1000000);
    callbackCount_ = 0;
    postFlushCallbacks_ = 0;
    appliedVolume_ = 0.0f;
    flushRequested_.store(false);
    disconnected_.store(false);

    // BOTH modes use LowLatency (MMAP) so getTimestamp stays tight. Using
    // PerformanceMode::None for solo/DIRECT was a CPU/glitch optimisation, but a
    // None (normal-mixer, non-MMAP) stream that runs first does NOT cleanly hand
    // the MMAP-exclusive output path back: the LowLatency stream opened right
    // after (the DIRECT->SYNC swap on a group join, or a launch already grouped)
    // then gets a degraded, sawtoothing getTimestamp and SYNC never locks. Solo
    // robustness instead comes from the deeper HAL buffer below; the deep PCM
    // ring absorbs the rest. Do NOT reintroduce None for DIRECT.
    const oboe::PerformanceMode perfMode = oboe::PerformanceMode::LowLatency;
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(perfMode)
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
    // Give the HAL slack: 2 bursts on the LowLatency path, more on the normal
    // path (bigger bursts, absorbs scheduling jitter). The deep ring (above) is
    // what actually absorbs GC, not this.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * (driftCorrection ? 2 : 4));
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

void SendspinOutputEngine::pauseStream() {
    if (!stream_) return;
    auto state = stream_->getState();
    if (state == oboe::StreamState::Started || state == oboe::StreamState::Starting) {
        stream_->requestStop();
    }
}

void SendspinOutputEngine::resumeStream() {
    if (!stream_) return;
    auto state = stream_->getState();
    if (state == oboe::StreamState::Started || state == oboe::StreamState::Starting) return;
    // Re-seat the timestamp anchor: after a stop/start the frame counters and
    // getTimestamp restart, so the old anchor is stale.
    anchorFramePosition_.store(-1);
    anchorTimeUs_.store(0);
    lastTimestampPollFrame_ = 0;
    stream_->requestStart();
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
    compEnv_ = 0.0f;
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
    // Output latency from Oboe's calculateLatencyMillis, NOT the raw getTimestamp
    // inflight (framesWritten - position). On some HALs (seen on Samsung MMAP)
    // the raw position sawtooths wildly (tens of ms, ~10 s period) even though
    // the true latency is a stable ~13 ms; feeding that into the drift made the
    // resampler over-correct continuously (audible glitches) while the audio was
    // actually in sync. calculateLatencyMillis stays stable, so the drift only
    // reflects real, sustained clock drift.
    auto calcLat = stream->calculateLatencyMillis();
    if (calcLat) {
        const int64_t latUs = static_cast<int64_t>(calcLat.value() * 1000.0);
        // Sane bound + EMA smoothing: the true HAL+DAC latency is ~constant, so
        // ignore absurd readings and let an occasional spike move the value only
        // a little. Keeps the timeline (and the displayed latency) steady so a
        // one-off HAL hiccup never triggers a resampler correction.
        if (latUs > 0 && latUs < 500000) {
            const int64_t prev = latencyUs_.load();
            if (prev <= 0) {
                latencyUs_.store(latUs);
            } else {
                int64_t d = latUs - prev;
                if (d > LAT_SLEW_US) d = LAT_SLEW_US;
                else if (d < -LAT_SLEW_US) d = -LAT_SLEW_US;
                latencyUs_.store(prev + d);
            }
        }
    }
}

int64_t SendspinOutputEngine::dacPresentationUsForNextWrite(int64_t /*framesWritten*/) const {
    // out[0] reaches the DAC after the (stable) output latency. Anchoring on the
    // raw getTimestamp position instead made dac0 sawtooth on flaky HALs and the
    // resampler over-correct; a stable latency keeps the drift steady so only
    // real, sustained clock drift is corrected (the resampler still tracks that).
    return monotonicNowUs() + latencyUs_.load();
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
    // below so mute/unmute/volume never jumps mid-waveform. While frozen the
    // target is 0 so we fade DOWN to silence on real audio (click-free) before
    // holding the read position.
    const bool frozen = frozen_.load();
    const float target = frozen ? 0.0f : volume_.load();
    const float g0 = appliedVolume_;
    const float maxStep = static_cast<float>(numFrames) / (static_cast<float>(sampleRate_) * FADE_SEC);
    float g1 = g0;
    if (target > g0) g1 = std::min(target, g0 + maxStep);
    else if (target < g0) g1 = std::max(target, g0 - maxStep);
    appliedVolume_ = g1;

    // Frozen and fully faded out: HOLD the read position so the buffered audio
    // survives the interruption. Output silence and return without draining;
    // when frozen_ clears the gain fades back up from this exact sample, so the
    // resume is instant and click-free (no flush, no rebuffer). The producer
    // keeps filling the ring (backpressured), so a quick interruption resumes
    // from a full buffer.
    if (frozen && g1 < 0.01f && g0 < 0.01f) {
        std::memset(out, 0, static_cast<size_t>(numFrames) * ch * sizeof(int16_t));
        return oboe::DataCallbackResult::Continue;
    }

    int64_t framesWritten = stream->getFramesWritten();
    refreshTimestampAnchor(stream, framesWritten);

    int64_t read = static_cast<int64_t>(readPos_);
    int64_t write = writeIndex_.load(std::memory_order_acquire);
    int64_t available = write - read;

    if (available <= 0) {
        // Ring empty = idle/paused (or waiting to refill). This is the expected
        // resting state, so emit silence WITHOUT logging or spending the
        // post-flush budget: an idle stream would otherwise spam one line per
        // ~4ms callback and flush the log of anything useful. The budget is kept
        // for the producing callbacks below, where post-flush recovery (drift,
        // rate, refill) is what we actually want to see.
        std::memset(out, 0, static_cast<size_t>(numFrames) * ch * sizeof(int16_t));
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
    // Publish the applied rate for the quality readout (1e6 = 1.0 = locked).
    lastRateMicros_.store(static_cast<int64_t>(rate * 1000000.0));

    // --- Compressor coefficients (amplitude-only; sync-safe) -------------
    // Precomputed once per callback from the live level. OFF (level 0) leaves
    // comp=false and the loop is an exact passthrough of the original behaviour.
    const int compLevel = compressorLevel_.load();
    const bool comp = compLevel >= 1 && compLevel <= 3;
    float thrDb = 0.0f, ratio = 1.0f, makeupDb = 0.0f, atkCoef = 0.0f, relCoef = 0.0f;
    if (comp) {
        const CompPreset& p = kCompPresets[compLevel];
        thrDb = p.thresholdDb;
        ratio = p.ratio;
        makeupDb = p.makeupDb;
        atkCoef = std::exp(-1.0f / (p.attackMs * 0.001f * static_cast<float>(sampleRate_)));
        relCoef = std::exp(-1.0f / (p.releaseMs * 0.001f * static_cast<float>(sampleRate_)));
    }

    // --- Resampled fill with gain fade (+ optional compression) ----------
    // Linear interpolation between ring[i0] and ring[i0+1] at the fractional
    // position; advancing pos by `rate` per output frame is the resampler. The
    // compressor (when on) derives a per-frame gain from a peak envelope and is
    // multiplied into the volume gain; output is clamped (makeup can exceed FS).
    const float span = static_cast<float>(numFrames);
    int produced = 0;
    for (int f = 0; f < numFrames - silenceFront; ++f) {
        const int64_t i0 = static_cast<int64_t>(pos);
        if (i0 + 1 >= write) break; // need i0 and i0+1 buffered, else underrun
        const float frac = static_cast<float>(pos - static_cast<double>(i0));
        const int64_t s0 = (i0 % capacityFrames_) * ch;
        const int64_t s1 = ((i0 + 1) % capacityFrames_) * ch;
        float smp[2];
        float peak = 0.0f;
        for (int c = 0; c < ch; ++c) {
            const float a = static_cast<float>(ring_[s0 + c]);
            const float b = static_cast<float>(ring_[s1 + c]);
            const float v = a + (b - a) * frac;
            smp[c] = v;
            const float av = v < 0.0f ? -v : v;
            if (av > peak) peak = av;
        }
        float compGain = 1.0f;
        if (comp) {
            const float in = peak * (1.0f / 32768.0f); // 0..1 full-scale
            const float coef = (in > compEnv_) ? atkCoef : relCoef;
            compEnv_ = in + coef * (compEnv_ - in);
            const float envDb = 20.0f * std::log10(compEnv_ > 1e-6f ? compEnv_ : 1e-6f);
            float grDb = 0.0f;
            if (envDb > thrDb) grDb = (envDb - thrDb) * (1.0f - 1.0f / ratio);
            compGain = std::pow(10.0f, (makeupDb - grDb) * (1.0f / 20.0f));
        }
        const float gain = (g0 + (g1 - g0) * static_cast<float>(silenceFront + f) / span) * compGain;
        int16_t* dp = out + static_cast<size_t>(silenceFront + f) * ch;
        for (int c = 0; c < ch; ++c) {
            float o = smp[c] * gain;
            if (o > 32767.0f) o = 32767.0f;
            else if (o < -32768.0f) o = -32768.0f;
            dp[c] = static_cast<int16_t>(o);
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
    // Disconnects (BT route change, or a phone call preempting the route) close
    // the stream. Flag it so the Kotlin engine reopens us — the AudioManager
    // device-callback does NOT fire when the device stays connected but is just
    // preempted (call audio), so we must surface the disconnect ourselves.
    LOGW("stream error after close: %s", oboe::convertToText(error));
    disconnected_.store(true);
}

} // namespace sendspin
