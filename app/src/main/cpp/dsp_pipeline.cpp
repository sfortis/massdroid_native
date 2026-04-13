#include "dsp_pipeline.h"
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <numeric>

#define LOG_TAG "AcousticCal"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

namespace acoustic {

std::vector<float> bandpassFilter(const int16_t* input, int length) {
    std::vector<float> output(length);
    double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;

    for (int i = 0; i < length; ++i) {
        double x0 = static_cast<double>(input[i]) / 32767.0;
        double y0 = BP_B0 * x0 + BP_B2 * x2 - BP_A1 * y1 - BP_A2 * y2;
        output[i] = static_cast<float>(y0);
        x2 = x1; x1 = x0;
        y2 = y1; y1 = y0;
    }
    return output;
}

std::vector<float> computeEnvelope(const float* signal, int length) {
    std::vector<float> env(length);
    float cur = 0.0f;

    for (int i = 0; i < length; ++i) {
        float a = std::fabs(signal[i]);
        if (a > cur) {
            cur = static_cast<float>(ENV_ATTACK * a + (1.0 - ENV_ATTACK) * cur);
        } else {
            cur = static_cast<float>((1.0 - ENV_RELEASE) * cur);
        }
        env[i] = cur;
    }
    return env;
}

CalibrationResult analyzeRecording(
    const std::vector<float>& envelope,
    const std::vector<int32_t>& toneOffsets,
    int recordPosAtPlayStart,
    int preRollSamples,
    int maxDelayMs,
    int sampleRate
) {
    const int envSize = static_cast<int>(envelope.size());
    const int toneCount = static_cast<int>(toneOffsets.size());

    // Peak envelope
    float peakEnvelope = *std::max_element(envelope.begin(), envelope.end());
    if (peakEnvelope < MIN_PEAK_ENVELOPE) {
        LOGW("Signal too weak: peak=%.6f", peakEnvelope);
        return CalibrationResult{0, 0, 0.0, 0.0f, 2}; // FAILED
    }

    // Noise floor from pre-roll region
    float noiseFloor = 0.0f;
    if (preRollSamples > 0 && preRollSamples <= envSize) {
        noiseFloor = std::accumulate(envelope.begin(),
                                     envelope.begin() + preRollSamples,
                                     0.0f) / preRollSamples;
    }

    float snrDb = (noiseFloor > 0.0f)
        ? 20.0f * std::log10(peakEnvelope / noiseFloor)
        : 40.0f;

    float threshold = noiseFloor + (peakEnvelope - noiseFloor) * ONSET_THRESHOLD_FRACTION;

    int minDelaySamp = (MIN_DELAY_MS * sampleRate) / 1000;
    int maxDelaySamp = (maxDelayMs * sampleRate) / 1000;

    std::vector<int64_t> delays;
    delays.reserve(toneCount);

    for (int i = 0; i < toneCount; ++i) {
        int recToneStart = recordPosAtPlayStart + toneOffsets[i];
        int searchStart  = recToneStart + minDelaySamp;
        int searchEnd    = std::min(recToneStart + maxDelaySamp, envSize);
        if (searchStart >= searchEnd) continue;

        int onsetSample = -1;
        for (int s = searchStart; s < searchEnd; ++s) {
            if (envelope[s] > threshold) {
                onsetSample = s;
                break;
            }
        }

        if (onsetSample >= 0) {
            int delaySamp = onsetSample - recToneStart;
            int64_t delayUs = (static_cast<int64_t>(delaySamp) * 1000000LL) / sampleRate;
            delays.push_back(delayUs);
            LOGD("Tone %d: delay=%lldms (recOffset=%d onset=%d)",
                 i, static_cast<long long>(delayUs / 1000), recToneStart, onsetSample);
        } else {
            LOGD("Tone %d: not detected (search %d..%d)", i, searchStart, searchEnd);
        }
    }

    // Analyze results
    int detected = static_cast<int>(delays.size());
    if (detected < MIN_TONES_DETECTED) {
        LOGW("Too few tones: %d/%d", detected, toneCount);
        return CalibrationResult{0, detected, 0.0, snrDb, 2}; // FAILED
    }

    std::sort(delays.begin(), delays.end());
    int64_t median = delays[delays.size() / 2];

    // MAD (Median Absolute Deviation) -> variance estimate
    std::vector<double> deviations(delays.size());
    for (size_t i = 0; i < delays.size(); ++i) {
        deviations[i] = std::fabs(static_cast<double>(delays[i] - median)) / 1000.0;
    }
    std::sort(deviations.begin(), deviations.end());
    double mad = deviations[deviations.size() / 2];
    double varianceMs = mad * 1.4826;

    // Quality assessment
    int quality;
    if (detected < MIN_TONES_DETECTED) {
        quality = 2; // FAILED
    } else if (varianceMs > MAX_VARIANCE_MS || snrDb < MIN_SNR_DB) {
        quality = 1; // MARGINAL
    } else {
        quality = 0; // GOOD
    }

    static const char* qualityNames[] = {"GOOD", "MARGINAL", "FAILED"};
    LOGD("Result: roundTrip=%lldms detected=%d/%d variance=%.1fms SNR=%.1fdB quality=%s",
         static_cast<long long>(median / 1000), detected, toneCount,
         varianceMs, snrDb, qualityNames[quality]);

    return CalibrationResult{median, detected, varianceMs, snrDb, quality};
}

} // namespace acoustic
