#pragma once

#include <cstdint>
#include <vector>

namespace acoustic {

// Audio constants (match Kotlin calibrator exactly)
constexpr int SAMPLE_RATE      = 48000;
constexpr int TONE_COUNT       = 6;
constexpr int TONE_DURATION_MS = 100;
constexpr int TONE_SPACING_MS  = 500;
constexpr int TONE_FREQ_HZ     = 1000;
constexpr float TONE_AMPLITUDE = 0.8f;
constexpr int RAMP_SAMPLES     = 96;   // 2ms at 48kHz
constexpr int PRE_ROLL_MS      = 300;
constexpr int TAIL_MS          = 600;

inline constexpr int msToSamples(int ms) { return (ms * SAMPLE_RATE) / 1000; }

struct ToneSequence {
    std::vector<int16_t> buffer;          // Full playback buffer (preroll + tones + tail)
    std::vector<int32_t> toneOffsets;     // Sample offset of each tone start in buffer
    int32_t preRollSamples;
    int32_t totalSamples;
};

// Build the complete playback buffer with preroll, tone bursts, spacing, and tail.
ToneSequence generateToneSequence();

} // namespace acoustic
