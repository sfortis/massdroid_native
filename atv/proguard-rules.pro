# The TV app is minified like the phone app (app/proguard-rules.pro). :core ships no consumer
# rules, so the rules the shared code needs are repeated here. JNI needs none: the native code
# never looks up Java classes, and proguard-android-optimize.txt keeps native methods.

# kotlinx.serialization (the Music Assistant and Sendspin messages are parsed in :core)
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
