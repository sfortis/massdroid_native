#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>

namespace sendspin {

/**
 * GC-immune Sendspin audio output.
 *
 * Why this exists: the Kotlin playback engine used to write each decoded PCM
 * chunk to an AudioTrack from a JVM thread, gating every chunk to its absolute
 * deadline (sleep-to-deadline). That keeps the AudioTrack FIFO shallow, which
 * IS the group-sync mechanism (every chunk re-anchors to the group timeline) —
 * but a shallow FIFO has no slack, so an ART GC pause on the JVM playback
 * thread misses a deadline and the DAC underruns (audible micro-drop).
 *
 * This class moves the OUTPUT to an Oboe callback running on a real-time HAL
 * thread (SCHED_FIFO), which is immune to JVM GC pauses. A deep, lock-free
 * SPSC ring sits between the Kotlin MediaCodec decode (producer) and this
 * callback (consumer): if the decode JVM thread GC-stalls, the deep ring
 * absorbs it. Per-chunk sync is preserved by doing the drift correction
 * INSIDE the callback (sample-accurate skip/insert against the group
 * timeline) instead of by keeping the buffer shallow. That is the one design
 * that gives deep-buffer GC-immunity AND per-chunk re-anchoring at once.
 *
 * Time domain: every PCM frame carries an intended CLOCK_MONOTONIC
 * presentation time (microseconds) for its first sample, supplied by the
 * producer (derived from the group clock). Oboe getTimestamp() reports DAC
 * presentation time in the same CLOCK_MONOTONIC domain, so the callback can
 * compare "when will the frame I am about to emit actually leave the DAC?"
 * against "when was this sample supposed to play?" and correct the difference.
 *
 * Threading: single-producer (Kotlin decode thread calls write()),
 * single-consumer (Oboe callback). flush()/stop()/release() come from a third
 * (control) thread and coordinate via atomics; see flushRequested_.
 */
class SendspinOutputEngine : public oboe::AudioStreamDataCallback,
                             public oboe::AudioStreamErrorCallback {
public:
    SendspinOutputEngine() = default;
    ~SendspinOutputEngine();

    // Opens and starts the Oboe output stream (PERFORMANCE_MODE_LOW_LATENCY,
    // I16, MONO/STEREO) and allocates the ring. The stream runs continuously,
    // emitting silence until the producer feeds data. Returns false on failure.
    //
    // driftCorrection: when true (grouped/SYNC) the callback aligns playback to
    // the absolute timeline (startup insert + tiny steady skip/insert). When
    // false (solo/DIRECT) the callback is a pure FIFO — there is no peer to
    // phase-lock to, so timeline correction only adds artifacts.
    bool start(int32_t sampleRate, int32_t channels, bool driftCorrection);

    // Stops and closes the stream and frees the ring.
    void stop();

    // Idle power management: requestStop the Oboe stream so the real-time HAL
    // callback stops firing (no CPU / no audio hardware held) WITHOUT closing it
    // or freeing the ring, so resumeStream() restarts quickly. Called when
    // playback has been idle for a grace period; resumed on the next stream.
    void pauseStream();
    void resumeStream();

    // Drops all buffered audio + timeline markers (seek / track change).
    // Safe to call from the control thread while the callback runs.
    void flush();

    // Producer push (Kotlin decode thread). pcm = interleaved int16 frames.
    // presentationLocalUs = intended CLOCK_MONOTONIC us of the FIRST frame.
    // Returns frames accepted (may be < frames if the ring is near full, which
    // should not happen in steady state given a multi-second ring).
    int32_t write(const int16_t* pcm, int32_t frames, int64_t presentationLocalUs);

    // Current output latency (DAC presentation lag) in microseconds, from the
    // cached Oboe timestamp anchor. 0 until the first valid timestamp.
    int64_t outputLatencyUs() const;

    // Buffered (not-yet-played) frames currently in the ring.
    int64_t bufferedFrames() const;

    // AAudio device id the stream is currently routed to (0 if not open). The
    // host matches this against AudioManager.getDevices() to recover the
    // device type / product name (Oboe has no routing listener).
    int32_t deviceId() const;

    // Smoothed timeline drift (intended - DAC presentation), microseconds.
    // Surfaced for the sync-error UI; correction itself is internal.
    int64_t driftEmaUs() const { return driftEmaUs_.load(); }

    // Cumulative ring-underrun frames (callback ran dry within a buffer): the
    // real audible-dropout counter for the quality readout. 0 = clean.
    int64_t underrunFrames() const { return underrunFrames_.load(); }

    // Last applied resampler rate, in micro-units (1000000 = 1.0). 1.0 = locked
    // passthrough; off-1.0 = actively correcting drift (SYNC only).
    int64_t resampleRateMicros() const { return lastRateMicros_.load(); }

    void setVolume(float v) { volume_.store(v); }

    // Dynamic-range compressor level: 0 = off (bypass), 1 = soft, 2 = medium,
    // 3 = hard. Amplitude-only (applied in the callback with the volume gain), so
    // it does not touch the timeline/ring/latency. Read live by the callback.
    void setCompressorLevel(int level) { compressorLevel_.store(level); }

    // Freeze/unfreeze the consumer WITHOUT dropping the ring (transient focus
    // loss in solo/DIRECT). While frozen the callback fades to silence and then
    // holds the read position, so the buffered audio survives and resumes
    // instantly + click-free on unfreeze. Unlike flush(), nothing is discarded.
    // No-op effect on a fresh stream (empty ring); intended for DIRECT only.
    void setFrozen(bool f) { frozen_.store(f); }

    // True after Oboe reported the stream disconnected (e.g. a phone call
    // preempts the route while the device stays "present" in getDevices(), so
    // the AudioManager device-callback never fires). The Kotlin engine polls
    // this and reopens the output; otherwise the dead stream never drains the
    // ring and playback is silent while the UI still shows "playing".
    bool isDisconnected() const { return disconnected_.load(); }

    int32_t bytesPerFrame() const { return channels_ * 2; }

    // Oboe callbacks (real-time audio thread).
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct Marker {
        int64_t frameIndex;     // absolute producer frame index this marker tags
        int64_t presentationUs; // intended CLOCK_MONOTONIC us of that frame
    };

    // Maps a ring frame index to its intended presentation time by linear
    // interpolation from the most recent preceding marker. Returns false if no
    // marker covers the index yet.
    bool intendedPresentationUs(int64_t frameIndex, int64_t* outUs);

    void resetRing();
    void refreshTimestampAnchor(oboe::AudioStream* stream, int64_t framesWritten);
    int64_t dacPresentationUsForNextWrite(int64_t framesWritten) const;

    static int64_t monotonicNowUs();

    std::shared_ptr<oboe::AudioStream> stream_;
    int32_t sampleRate_ = 48000;
    int32_t channels_ = 2;

    // Lock-free SPSC ring of interleaved int16 samples. Indices are absolute
    // frame counters (monotonic); the physical slot is index % capacityFrames.
    std::unique_ptr<int16_t[]> ring_;
    int32_t capacityFrames_ = 0;
    std::atomic<int64_t> writeIndex_{0}; // next frame the producer will write
    std::atomic<int64_t> readIndex_{0};  // floor(readPos_), published for the producer
    // Fractional consumer position (callback thread only). Advancing it by a
    // rate != 1.0 with linear interpolation IS the steady-state resampler.
    double readPos_ = 0.0;

    // Timeline markers (one per write()), small SPSC ring. Indices are
    // absolute counters (mod MARKER_CAP for the slot); int64 so a multi-hour
    // session cannot overflow them.
    static constexpr int MARKER_CAP = 512;
    Marker markers_[MARKER_CAP];
    std::atomic<int64_t> markerWrite_{0};
    std::atomic<int64_t> markerRead_{0};

    // volume_ is the TARGET gain (set from Kotlin). appliedVolume_ is the gain
    // actually in effect, ramped toward the target a little each callback so
    // mute/unmute/volume changes fade over ~FADE_SEC instead of clicking
    // mid-waveform. appliedVolume_ is touched only by the callback thread.
    std::atomic<float> volume_{1.0f};
    float appliedVolume_ = 0.0f;
    // Compressor level (0..3), set from Kotlin, read live by the callback.
    std::atomic<int> compressorLevel_{0};
    // Peak envelope follower state (callback thread only); reset on ring reset.
    float compEnv_ = 0.0f;
    std::atomic<bool> flushRequested_{false};
    std::atomic<bool> driftCorrection_{true};
    // Freeze: hold the read position (preserve the ring) across a transient
    // interruption. Faded in/out via the existing gain ramp so it is click-free.
    std::atomic<bool> frozen_{false};
    // Set by onErrorAfterClose when Oboe disconnects the stream; cleared on start.
    std::atomic<bool> disconnected_{false};

    // Output-latency anchor captured from Oboe getTimestamp (CLOCK_MONOTONIC),
    // refreshed periodically inside the callback.
    std::atomic<int64_t> anchorFramePosition_{-1};
    std::atomic<int64_t> anchorTimeUs_{0};
    std::atomic<int64_t> latencyUs_{0};
    int64_t lastTimestampPollFrame_ = 0;

    // Diagnostics
    std::atomic<int64_t> driftEmaUs_{0};
    std::atomic<int64_t> underrunFrames_{0};
    std::atomic<int64_t> lastRateMicros_{1000000}; // applied resampler rate * 1e6
    int64_t callbackCount_ = 0;
    // When >0, log every callback (decremented). Armed when a flush is acked so
    // we can see exactly what the callback emits across a skip/seek boundary.
    int postFlushCallbacks_ = 0;
};

} // namespace sendspin
