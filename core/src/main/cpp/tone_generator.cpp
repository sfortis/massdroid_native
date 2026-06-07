#include "tone_generator.h"
#include <cmath>
#include <algorithm>

namespace acoustic {

static std::vector<int16_t> generateSingleTone() {
    const int numSamples = msToSamples(TONE_DURATION_MS);
    std::vector<int16_t> samples(numSamples);

    for (int i = 0; i < numSamples; ++i) {
        double t = static_cast<double>(i) / SAMPLE_RATE;

        // Sharp 2ms linear ramp for attack/release
        float ramp;
        if (i < RAMP_SAMPLES) {
            ramp = static_cast<float>(i) / RAMP_SAMPLES;
        } else if (i > numSamples - RAMP_SAMPLES) {
            ramp = static_cast<float>(numSamples - i) / RAMP_SAMPLES;
        } else {
            ramp = 1.0f;
        }

        double value = TONE_AMPLITUDE * ramp * std::sin(2.0 * M_PI * TONE_FREQ_HZ * t);
        int32_t intVal = static_cast<int32_t>(value * 32767.0);
        intVal = std::clamp(intVal, static_cast<int32_t>(-32768), static_cast<int32_t>(32767));
        samples[i] = static_cast<int16_t>(intVal);
    }
    return samples;
}

ToneSequence generateToneSequence() {
    ToneSequence seq;

    const int preRollSamples = msToSamples(PRE_ROLL_MS);
    const int toneSamples    = msToSamples(TONE_DURATION_MS);
    const int spacingSamples = msToSamples(TONE_SPACING_MS);
    const int tailSamples    = msToSamples(TAIL_MS);
    const int totalSamples   = preRollSamples
                             + TONE_COUNT * (toneSamples + spacingSamples)
                             + tailSamples;

    seq.buffer.resize(totalSamples, 0);  // Zero-initialized (silence)
    seq.toneOffsets.resize(TONE_COUNT);
    seq.preRollSamples = preRollSamples;
    seq.totalSamples = totalSamples;

    auto tone = generateSingleTone();

    int pos = preRollSamples;
    for (int i = 0; i < TONE_COUNT; ++i) {
        seq.toneOffsets[i] = pos;
        std::copy(tone.begin(), tone.end(), seq.buffer.begin() + pos);
        pos += toneSamples + spacingSamples;
    }

    return seq;
}

} // namespace acoustic
