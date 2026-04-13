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
    CalibrationResult measureRoundTrip(int maxDelayMs, ProgressCallback progress);

    // Oboe callbacks (run on SCHED_FIFO audio threads)
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

    void onErrorBeforeClose(
        oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStreams();
    void closeStreams();
    void updateProgress(int32_t playPos);

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
