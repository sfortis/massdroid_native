plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("io.gitlab.arturbosch.detekt")
    kotlin("kapt")
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "net.asksakis.massdroidv2.tv"
    compileSdk = 36
    // The same NDK as :core. Without it AGP looks for its own default NDK, which is not
    // installed, and then neither strips the native libraries of the APK nor extracts
    // their symbols for Play (it reports them as already stripped). F-Droid builds with
    // this NDK too (`ndk:` in the fdroiddata metadata).
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "net.asksakis.massdroidv2.tv"
        minSdk = 26
        targetSdk = 35
        // Versioned in lockstep with the phone app so the TV APK in a release matches the tag.
        versionCode = 45
        versionName = "2.39.2"

        // Only the ABIs :core builds its native code for. Without this filter, AndroidX libraries
        // add a lib/x86 folder without our native libraries, so the app would install on an x86
        // device and crash when Sendspin loads its output library.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 drops the unused code (most of material-icons-extended, and the self-updater in
            // the play and fdroid flavors) and stays reproducible for F-Droid, as the phone app is.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Unsigned when no keystore is configured, as in the phone app: F-Droid builds the
            // fdroid flavor without our key, after deleting the signingConfigs block above.
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
            // Reproducible build: keep the commit hash out of the manifest.
            vcsInfo.include = false
        }
    }

    // The same reproducible-build settings as the phone app: no AGP dependency metadata block,
    // which F-Droid's scanner flags, and no generated baseline profile, whose ordering differs
    // between build hosts.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    tasks.whenTaskAdded {
        if (name.contains("ArtProfile")) {
            enabled = false
        }
    }

    // github = the GitHub release APK, with the in-app updater. fdroid = the F-Droid build: the
    // updater is switched off and its install permission removed (src/fdroid/AndroidManifest.xml),
    // because F-Droid updates the app through its own repository. play = the Google Play build: no
    // updater and no install permission either (src/play/AndroidManifest.xml), published under the
    // phone app's package (see androidComponents below).
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_STORE", "false")
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_STORE", "false")
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_STORE", "true")
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "false")
            // The native symbols go into the AAB, so Play shows function names in native
            // crashes. Only the Play flavor: the F-Droid build must stay reproducible.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            // variant.name carries the flavor (githubRelease, fdroidRelease), so the two
            // flavors do not collide on one file name.
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "massdroid-tv-${variant.versionName}-${variant.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            // BouncyCastle (Sendspin Noise) ships an OSGi manifest another jar also has; Android never reads it.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG keeps the GitHub updater off in debug builds, whose package
        // (.tv.debug) the release APK would not update; ENABLE_UPDATE_CHECK keeps it off in
        // the fdroid flavor.
        buildConfig = true
    }
}

// On Google Play the TV app ships in the phone app's listing, on the dedicated Android TV track, so
// the play build takes the phone's package and a versionCode of 2_000_000 + N that no phone or
// automotive bundle uses (app/build.gradle.kts). Both are set here rather than in the flavor,
// because F-Droid reads the package and the version from this file with a regex and must keep
// seeing the TV package and N. The GitHub and F-Droid builds keep the TV package.
val playTvApplicationId = "net.asksakis.massdroidv2"
val playTvVersionOffset = 2_000_000

androidComponents {
    onVariants(selector().withFlavor("distribution" to "play")) { variant ->
        val suffix = if (variant.buildType == "debug") ".debug" else ""
        variant.applicationId.set(playTvApplicationId + suffix)
        variant.outputs.forEach { output ->
            output.versionCode.set(output.versionCode.get() + playTvVersionOffset)
        }
    }
}

dependencies {
    // Shared non-UI logic (data, domain, sendspin engine + coordination, native)
    implementation(project(":core"))
    implementation(project(":ui-shared"))

    // Compose (TV)
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Core platform
    implementation("androidx.core:core-ktx:1.16.0")

    // MediaSession (now-playing surface on the Android TV home / system controls)
    implementation("androidx.media:media:1.7.1")
}

kapt {
    correctErrorTypes = true
}

detekt {
    config.setFrom("$rootDir/detekt.yml")
}
