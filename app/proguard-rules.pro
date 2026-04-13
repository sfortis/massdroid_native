# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class net.asksakis.massdroidv2.**$$serializer { *; }
-keepclassmembers class net.asksakis.massdroidv2.** { *** Companion; }
-keepclasseswithmembers class net.asksakis.massdroidv2.** { kotlinx.serialization.KSerializer serializer(...); }

# Strip debug/verbose logs in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Native acoustic calibrator (JNI bridge)
-keep class net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator {
    native <methods>;
    private void onNativeProgress(int, int);
}
-keep class net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator$CalibrationResult { *; }
-keep class net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator$Quality { *; }
