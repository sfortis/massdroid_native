#pragma once

#include <cstdint>
#include <vector>

namespace acoustic {

struct CalibrationResult {
    int64_t roundTripUs   = 0;
    int     detectedTones = 0;
    double  varianceMs    = 0.0;
    float   snrDb         = 0.0f;
    int     quality       = 2;  // 0=GOOD, 1=MARGINAL, 2=FAILED
};

// Bandpass filter coefficients: 2nd order IIR centered at 1kHz, Q~5, for 48kHz
constexpr double BP_B0 = 0.06206;
constexpr double BP_B2 = -0.06206;
constexpr double BP_A1 = -1.86891;
constexpr double BP_A2 = 0.87588;

// Envelope follower
constexpr double ENV_ATTACK  = 0.05;
constexpr double ENV_RELEASE = 0.0005;

// Detection thresholds
constexpr float ONSET_THRESHOLD_FRACTION = 0.15f;
constexpr float MIN_PEAK_ENVELOPE = 0.003f;
constexpr int   MIN_DELAY_MS      = 3;
constexpr int   MIN_TONES_DETECTED = 4;
constexpr double MAX_VARIANCE_MS   = 8.0;
constexpr float  MIN_SNR_DB        = 6.0f;

// Apply 2nd order IIR bandpass filter at 1kHz.
std::vector<float> bandpassFilter(const int16_t* input, int length);

// Compute envelope via attack/release follower on rectified signal.
std::vector<float> computeEnvelope(const float* signal, int length);

// Run onset detection and statistical analysis on the recorded envelope.
// Returns CalibrationResult with round-trip latency, quality assessment, etc.
CalibrationResult analyzeRecording(
    const std::vector<float>& envelope,
    const std::vector<int32_t>& toneOffsets,
    int recordPosAtPlayStart,
    int preRollSamples,
    int maxDelayMs,
    int sampleRate
);

} // namespace acoustic
