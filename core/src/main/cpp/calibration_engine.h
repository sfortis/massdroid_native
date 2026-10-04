#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace acoustic {

constexpr int SAMPLE_RATE = 48000;

// Output of one playback run. outputFrame0Nanos places the played signal on
// the CLOCK_MONOTONIC axis as the output stream's getTimestamp reports it, so
// the caller can measure how much later the signal reached the microphone.
struct PlayResult {
    bool ok = false;
    // Presentation time of signal frame 0 (median over the run).
    int64_t outputFrame0Nanos = 0;
    // Largest distance of a single timestamp sample from the median.
    int64_t outputSpreadNanos = 0;
    int timestampSamples = 0;
    int xRuns = 0;
    int routedOutputDeviceId = 0;
    int sampleRate = 0;
    // Stream frames written before signal frame 0 (normally 0).
    int64_t frameOffset = 0;
};

/**
 * Plays mono test signals through an output stream opened exactly like the
 * Sendspin output (Shared, LowLatency, I16, stereo, Media/Music), so they take
 * the same mixer and effect path as the music, and reports when the output says
 * it presented each signal's first frame.
 *
 * The stream stays open between signals and plays silence, so a calibration
 * can measure repeatedly while the output path keeps running: some Bluetooth
 * speakers grow their own buffer for minutes after audio starts, and only a
 * path that keeps running reaches the delay the music will have.
 */
class CalibrationEngine : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    CalibrationEngine() = default;
    ~CalibrationEngine() override;

    // Opens and starts the stream, which plays silence until a signal is
    // queued. outputDeviceId 0 leaves the route to the framework; a device id
    // pins it (best effort, check routedOutputDeviceId).
    bool open(int outputDeviceId);

    // Blocking: plays the signal from the next callback on and returns after it
    // has been presented, or on error or timeout. Requires open().
    PlayResult playSignal(const std::vector<int16_t>& signal);

    void close();

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

    void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    static constexpr int TIMESTAMP_CAPACITY = 128;

    // What the callback plays: silence, a signal waiting for the next callback,
    // or a signal in progress.
    enum class BlockState { SILENT, PENDING, PLAYING, DONE };

    void sampleTimestamp(oboe::AudioStream* stream);
    bool medianFrame0(int64_t& value, int64_t& spread) const;

    std::shared_ptr<oboe::AudioStream> stream_;
    std::vector<int16_t> signal_;
    std::atomic<BlockState> state_{BlockState::SILENT};
    int64_t playPos_ = 0;
    int64_t frameOffset_ = -1;
    int blockCallbacks_ = 0;

    int64_t frame0Nanos_[TIMESTAMP_CAPACITY] = {};
    std::atomic<int> timestampCount_{0};

    std::atomic<bool> error_{false};
    std::mutex mutex_;
    std::condition_variable cv_;
};

} // namespace acoustic
