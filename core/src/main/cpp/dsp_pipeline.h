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
    // Microphone input-path latency reported by Oboe
    // (AAudio.calculateLatencyMillis on the input stream). Reports only the
    // AAudio HAL buffer occupancy — DSP/processing stages that sit between
    // the analog mic and the AAudio buffer are NOT included. Use the
    // outputHALUs + chirp round trip on a phone-speaker reference pass to
    // measure the full mic-path latency instead.
    int64_t inputLatencyUs = 0;
    // Output pipeline latency measured from Oboe getTimestamp() during the
    // chirp playback — the time between calling requestStart() and the
    // first audio frame leaving the routed DAC. Meaningful only when the
    // routed output is an in-phone device (built-in speaker, wired, USB):
    // BT output reports the time frames were handed to the BT transport
    // layer, not actual speaker playback, so this value is opaque for BT.
    // Callers should consult routedOutputDeviceId/Type when interpreting.
    int64_t outputHALUs = 0;
    // AAudio device ids that the two streams actually opened on. Lets the
    // caller verify whether a routing override (via outputDeviceId) took
    // effect; if the value differs from the requested id, callers should
    // treat the measurement as an "active output" pass rather than a
    // forced-speaker reference pass.
    int32_t routedOutputDeviceId = 0;
    int32_t routedInputDeviceId  = 0;
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
