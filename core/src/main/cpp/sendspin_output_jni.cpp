#include <jni.h>
#include <android/log.h>

#include "sendspin_output_engine.h"

#define LOG_TAG "SendspinNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using sendspin::SendspinOutputEngine;

extern "C" {

JNIEXPORT jlong JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeCreate(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new SendspinOutputEngine());
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) delete reinterpret_cast<SendspinOutputEngine*>(ptr);
}

JNIEXPORT jboolean JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeStart(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jint sampleRate, jint channels,
    jboolean driftCorrection) {
    if (ptr == 0) return JNI_FALSE;
    auto* engine = reinterpret_cast<SendspinOutputEngine*>(ptr);
    return engine->start(sampleRate, channels, driftCorrection == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->stop();
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeFlush(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->flush();
}

JNIEXPORT jint JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeWrite(
    JNIEnv* env, jobject /*thiz*/, jlong ptr, jbyteArray pcm, jint offset, jint length,
    jlong presentationLocalUs) {
    if (ptr == 0 || pcm == nullptr || length <= 0) return 0;
    auto* engine = reinterpret_cast<SendspinOutputEngine*>(ptr);
    const int32_t bytesPerFrame = engine->bytesPerFrame();
    if (bytesPerFrame <= 0) return 0;
    const int32_t frames = length / bytesPerFrame;
    if (frames <= 0) return 0;

    // Zero-copy access to the Java PCM buffer; engine->write only does a memcpy
    // into the ring (no JNI calls), so the critical section stays short.
    void* base = env->GetPrimitiveArrayCritical(pcm, nullptr);
    if (base == nullptr) return 0;
    const auto* samples = reinterpret_cast<const int16_t*>(
        static_cast<const uint8_t*>(base) + offset);
    int32_t written = engine->write(samples, frames, presentationLocalUs);
    env->ReleasePrimitiveArrayCritical(pcm, base, JNI_ABORT);
    return written;
}

JNIEXPORT jlong JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeOutputLatencyUs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->outputLatencyUs();
}

JNIEXPORT jlong JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeBufferedFrames(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->bufferedFrames();
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeSetVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jfloat volume) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->setVolume(volume);
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeSetFrozen(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jboolean frozen) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->setFrozen(frozen == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeSetCompressorLevel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jint level) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->setCompressorLevel(level);
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeSetDither(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jboolean enabled) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->setDither(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativePauseStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->pauseStream();
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeResumeStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) reinterpret_cast<SendspinOutputEngine*>(ptr)->resumeStream();
}

JNIEXPORT jboolean JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeIsDisconnected(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return JNI_FALSE;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->isDisconnected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeDeviceId(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->deviceId();
}

JNIEXPORT jlong JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeDriftEmaUs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->driftEmaUs();
}

JNIEXPORT jlong JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeUnderrunFrames(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 0;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->underrunFrames();
}

JNIEXPORT jlong JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_SendspinNativeOutput_nativeResampleRateMicros(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return 1000000;
    return reinterpret_cast<SendspinOutputEngine*>(ptr)->resampleRateMicros();
}

} // extern "C"
