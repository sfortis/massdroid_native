#include <jni.h>
#include <android/log.h>

#include "calibration_engine.h"

#define LOG_TAG "AcousticCal"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
// Layout of the LongArray nativePlaySignal fills; mirrored in NativeAcousticCalibrator.
enum ResultSlot {
    SLOT_FRAME0_NANOS = 0,
    SLOT_SPREAD_NANOS,
    SLOT_TIMESTAMP_SAMPLES,
    SLOT_XRUNS,
    SLOT_ROUTED_DEVICE_ID,
    SLOT_SAMPLE_RATE,
    SLOT_FRAME_OFFSET,
    SLOT_COUNT
};
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_NativeAcousticCalibrator_nativeCreate(
    JNIEnv* /*env*/, jobject /*thiz*/
) {
    return reinterpret_cast<jlong>(new acoustic::CalibrationEngine());
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_NativeAcousticCalibrator_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr
) {
    delete reinterpret_cast<acoustic::CalibrationEngine*>(enginePtr);
}

JNIEXPORT jboolean JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_NativeAcousticCalibrator_nativeOpen(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint outputDeviceId
) {
    if (enginePtr == 0) return JNI_FALSE;
    auto* engine = reinterpret_cast<acoustic::CalibrationEngine*>(enginePtr);
    return engine->open(outputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_NativeAcousticCalibrator_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr
) {
    if (enginePtr == 0) return;
    reinterpret_cast<acoustic::CalibrationEngine*>(enginePtr)->close();
}

JNIEXPORT jboolean JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_NativeAcousticCalibrator_nativePlaySignal(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jshortArray signal, jlongArray resultOut
) {
    if (enginePtr == 0 || signal == nullptr || resultOut == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(resultOut) < SLOT_COUNT) {
        LOGE("nativePlaySignal: result array too short");
        return JNI_FALSE;
    }
    const jsize length = env->GetArrayLength(signal);
    std::vector<int16_t> pcm(static_cast<size_t>(length));
    env->GetShortArrayRegion(signal, 0, length, pcm.data());

    auto* engine = reinterpret_cast<acoustic::CalibrationEngine*>(enginePtr);
    const acoustic::PlayResult result = engine->playSignal(pcm);

    jlong values[SLOT_COUNT];
    values[SLOT_FRAME0_NANOS] = result.outputFrame0Nanos;
    values[SLOT_SPREAD_NANOS] = result.outputSpreadNanos;
    values[SLOT_TIMESTAMP_SAMPLES] = result.timestampSamples;
    values[SLOT_XRUNS] = result.xRuns;
    values[SLOT_ROUTED_DEVICE_ID] = result.routedOutputDeviceId;
    values[SLOT_SAMPLE_RATE] = result.sampleRate;
    values[SLOT_FRAME_OFFSET] = result.frameOffset;
    env->SetLongArrayRegion(resultOut, 0, SLOT_COUNT, values);
    return result.ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
