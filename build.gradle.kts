plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

// Redirect build output to a local filesystem ONLY when explicitly requested
// (source may live on a remote/NFS mount). Default to the STANDARD Gradle layout
// so F-Droid's `fdroid build` (which sets no property) finds the APK where it
// expects it; redirecting by default broke the F-Droid build (it looked for the
// APK under build/ but everything went to ~/massdroid-native-build). The dev
// build-install.sh passes -PmassdroidBuildRoot to keep using the local mount.
val configuredBuildRoot = providers.gradleProperty("massdroidBuildRoot").orNull
    ?: System.getenv("MASSDROID_BUILD_ROOT")
if (configuredBuildRoot != null) {
    val localBuildRoot = file(configuredBuildRoot)
    allprojects {
        val subDir = if (path == ":") "root" else path.removePrefix(":").replace(":", "/")
        layout.buildDirectory = localBuildRoot.resolve(subDir)
    }
}
