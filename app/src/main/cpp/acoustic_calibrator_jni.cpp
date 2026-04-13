#include <jni.h>
#include <android/log.h>

#include "calibration_engine.h"

#define LOG_TAG "AcousticCal"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* gJvm = nullptr;

// Cached JNI references for CalibrationResult construction
static jclass    gResultClass    = nullptr;
static jmethodID gResultCtor     = nullptr;
static jclass    gQualityClass   = nullptr;
static jfieldID  gQualityGood    = nullptr;
static jfieldID  gQualityMarginal = nullptr;
static jfieldID  gQualityFailed  = nullptr;

// Cached reference for progress callback
static jmethodID gProgressMethod = nullptr;

static bool ensureResultClassCached(JNIEnv* env) {
    if (gResultClass != nullptr) return true;

    jclass localResult = env->FindClass(
        "net/asksakis/massdroidv2/data/sendspin/NativeAcousticCalibrator$CalibrationResult");
    if (!localResult) {
        LOGE("Cannot find CalibrationResult class");
        return false;
    }
    gResultClass = static_cast<jclass>(env->NewGlobalRef(localResult));
    env->DeleteLocalRef(localResult);

    gResultCtor = env->GetMethodID(gResultClass, "<init>",
        "(JIDFLnet/asksakis/massdroidv2/data/sendspin/NativeAcousticCalibrator$Quality;)V");
    if (!gResultCtor) {
        LOGE("Cannot find CalibrationResult constructor");
        return false;
    }

    jclass localQuality = env->FindClass(
        "net/asksakis/massdroidv2/data/sendspin/NativeAcousticCalibrator$Quality");
    if (!localQuality) {
        LOGE("Cannot find Quality class");
        return false;
    }
    gQualityClass = static_cast<jclass>(env->NewGlobalRef(localQuality));
    env->DeleteLocalRef(localQuality);

    gQualityGood = env->GetStaticFieldID(gQualityClass, "GOOD",
        "Lnet/asksakis/massdroidv2/data/sendspin/NativeAcousticCalibrator$Quality;");
    gQualityMarginal = env->GetStaticFieldID(gQualityClass, "MARGINAL",
        "Lnet/asksakis/massdroidv2/data/sendspin/NativeAcousticCalibrator$Quality;");
    gQualityFailed = env->GetStaticFieldID(gQualityClass, "FAILED",
        "Lnet/asksakis/massdroidv2/data/sendspin/NativeAcousticCalibrator$Quality;");

    if (!gQualityGood || !gQualityMarginal || !gQualityFailed) {
        LOGE("Cannot find Quality enum fields");
        return false;
    }

    return true;
}

static bool ensureProgressMethodCached(JNIEnv* env, jobject thiz) {
    if (gProgressMethod != nullptr) return true;

    jclass cls = env->GetObjectClass(thiz);
    gProgressMethod = env->GetMethodID(cls, "onNativeProgress", "(II)V");
    env->DeleteLocalRef(cls);

    if (!gProgressMethod) {
        LOGE("Cannot find onNativeProgress method");
        return false;
    }
    return true;
}

static jobject qualityToJava(JNIEnv* env, int quality) {
    jfieldID field;
    switch (quality) {
        case 0:  field = gQualityGood; break;
        case 1:  field = gQualityMarginal; break;
        default: field = gQualityFailed; break;
    }
    return env->GetStaticObjectField(gQualityClass, field);
}

static jobject resultToJava(JNIEnv* env, const acoustic::CalibrationResult& result) {
    jobject qualityObj = qualityToJava(env, result.quality);
    return env->NewObject(gResultClass, gResultCtor,
        static_cast<jlong>(result.roundTripUs),
        static_cast<jint>(result.detectedTones),
        static_cast<jdouble>(result.varianceMs),
        static_cast<jfloat>(result.snrDb),
        qualityObj);
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_NativeAcousticCalibrator_nativeCreate(
    JNIEnv* env, jobject /*thiz*/
) {
    if (!ensureResultClassCached(env)) {
        return 0;
    }
    auto* engine = new acoustic::CalibrationEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_NativeAcousticCalibrator_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr
) {
    if (enginePtr != 0) {
        delete reinterpret_cast<acoustic::CalibrationEngine*>(enginePtr);
    }
}

JNIEXPORT jobject JNICALL
Java_net_asksakis_massdroidv2_data_sendspin_NativeAcousticCalibrator_nativeMeasure(
    JNIEnv* env, jobject thiz, jlong enginePtr, jint maxDelayMs
) {
    if (enginePtr == 0) {
        LOGE("nativeMeasure called with null engine");
        acoustic::CalibrationResult failed{0, 0, 0.0, 0.0f, 2};
        return resultToJava(env, failed);
    }

    auto* engine = reinterpret_cast<acoustic::CalibrationEngine*>(enginePtr);

    // Set up progress callback that calls back into Kotlin
    ensureProgressMethodCached(env, thiz);
    jobject globalThiz = env->NewGlobalRef(thiz);

    acoustic::ProgressCallback progressCb = [globalThiz](int toneIndex, int total) {
        JNIEnv* callbackEnv = nullptr;
        bool needDetach = false;

        if (gJvm->GetEnv(reinterpret_cast<void**>(&callbackEnv), JNI_VERSION_1_6) != JNI_OK) {
            if (gJvm->AttachCurrentThread(&callbackEnv, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread for progress callback");
                return;
            }
            needDetach = true;
        }

        if (gProgressMethod && callbackEnv) {
            callbackEnv->CallVoidMethod(globalThiz, gProgressMethod,
                                        static_cast<jint>(toneIndex),
                                        static_cast<jint>(total));
        }

        if (needDetach) {
            gJvm->DetachCurrentThread();
        }
    };

    acoustic::CalibrationResult result = engine->measureRoundTrip(maxDelayMs, progressCb);

    env->DeleteGlobalRef(globalThiz);

    return resultToJava(env, result);
}

} // extern "C"
