plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

// Redirect build output to local filesystem (source lives on S3 remote mount)
val localBuildRoot = file(System.getProperty("user.home") + "/massdroid-native-build")
allprojects {
    val subDir = if (path == ":") "root" else path.removePrefix(":").replace(":", "/")
    layout.buildDirectory = localBuildRoot.resolve(subDir)
}
