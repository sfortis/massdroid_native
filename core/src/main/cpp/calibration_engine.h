#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <vector>

#include "dsp_pipeline.h"
#include "tone_generator.h"

namespace acoustic {

using ProgressCallback = std::function<void(int toneIndex, int total)>;

class CalibrationEngine : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    CalibrationEngine();
    ~CalibrationEngine();

    // Blocking call: runs entire calibration cycle (~4-5 seconds).
    // Called from JNI thread (Dispatchers.Default), waits on condition_variable.
    //
    // outputDeviceId: AAudio device id to route the output stream to. Pass 0
    // (the AAudio "unspecified" value) to let the framework pick the default
    // route (used when calibrating whatever output the user is on, e.g.
    // BT). Pass a built-in speaker device id to force a phone-speaker
    // reference pass (used to characterize mic_path independent of the
    // active BT output). Setting a device id is best-effort: the framework
    // may silently ignore it, so callers must verify the actual routed
    // device on the returned result.
    CalibrationResult measureRoundTrip(int maxDelayMs, int outputDeviceId,
                                       ProgressCallback progress);

    // Oboe callbacks (run on SCHED_FIFO audio threads)
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

    void onErrorBeforeClose(
        oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStreams(int outputDeviceId);
    void closeStreams();
    void updateProgress(int32_t playPos);

    // Output-stream latency anchor captured from Oboe getTimestamp while
    // playback is RUNNING. (position, time) pair lets the host extrapolate
    // when frame 0 left the DAC, which combined with requestStart() time
    // gives the OUTPUT path latency. Useful only when the routed device is
    // an in-phone DAC: BT output reports the time when frames were handed
    // to the BT transport, not when the BT speaker played them, so this
    // value is meaningless for BT.
    std::atomic<int64_t> outputAnchorFramePosition_{-1};
    std::atomic<int64_t> outputAnchorTimeNanos_{0};
    std::atomic<int64_t> outputStartRequestTimeNanos_{0};
    std::atomic<int>     outputTimestampQueryAttempts_{0};
    static constexpr int MAX_OUTPUT_TIMESTAMP_ATTEMPTS = 10;
    // Resolved AAudio device id of each stream after open() succeeded — used
    // by callers to verify that an outputDeviceId routing request actually
    // took effect.
    int32_t routedOutputDeviceId_{0};
    int32_t routedInputDeviceId_{0};

    // Oboe streams
    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::shared_ptr<oboe::AudioStream> inputStream_;

    // Playback state
    ToneSequence toneSeq_;
    std::atomic<int32_t> playPos_{0};
    std::atomic<bool> playbackDone_{false};

    // Recording state
    std::vector<int16_t> recordBuffer_;
    std::atomic<int32_t> recordPos_{0};
    std::atomic<bool> recordingDone_{false};

    // Sync anchor: recording position when output starts
    std::atomic<int32_t> recordPosAtPlayStart_{-1};
    std::atomic<bool> outputStarted_{false};

    // Input-path latency captured from the input stream while it is RUNNING.
    // AAudio's getTimestamp() (and therefore calculateLatencyMillis) returns
    // ErrorInvalidState once the data callback returns Stop, so we cannot
    // query after wait_for completes — we have to grab it from inside the
    // input callback. capLatencyAttempts caps how many callbacks we spend
    // trying so a permanently-unsupported stream doesn't keep retrying.
    std::atomic<int64_t> capturedInputLatencyUs_{0};
    std::atomic<int>     latencyQueryAttempts_{0};
    static constexpr int MAX_LATENCY_QUERY_ATTEMPTS = 10;

    // Error flag
    std::atomic<bool> errorOccurred_{false};

    // Completion signaling
    std::mutex completionMutex_;
    std::condition_variable completionCv_;

    // Progress
    ProgressCallback progressCallback_;
    std::atomic<int> lastReportedTone_{0};

    // Tone sample count (for progress tracking)
    int toneSamples_ = 0;
};

} // namespace acoustic
