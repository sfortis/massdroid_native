plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    kotlin("kapt")
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "net.asksakis.massdroidv2"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.asksakis.massdroidv2"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "2.29.0"
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
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // Reproducible-build: strip VCS info so the commit hash AGP embeds in
            // the manifest does not differ between build hosts. AGP 8.3+.
            vcsInfo.include = false
        }
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    // F-Droid scanner flags the AGP-emitted "Dependency metadata" extra signing
    // block as non-free content. Disable for both APK and AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Reproducible-build: disable AGP's auto-generated baseline profile; the
    // .prof/.profm ordering is non-deterministic across build hosts and its
    // checksum leaks into classes.dex via R8 (F-Droid Reproducible_Builds guide).
    tasks.whenTaskAdded {
        if (name.contains("ArtProfile")) {
            enabled = false
        }
    }

    // github = default (in-app updater polls GitHub Releases, installs via
    // REQUEST_INSTALL_PACKAGES). fdroid = updater compiled out + the install
    // permission stripped via src/fdroid/AndroidManifest.xml; F-Droid updates
    // through its own repository.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "false")
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            // variant.name includes the flavor (e.g. githubRelease / fdroidRelease)
            // so the two flavors do not collide on a single filename.
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "massdroid-${variant.versionName}-${variant.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("sh.calvin.reorderable:reorderable:3.0.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("com.caverock:androidsvg-aar:1.4")

    // Custom Tabs for OAuth flows (Home Assistant SSO)
    implementation("androidx.browser:browser:1.8.0")

    // Palette for dynamic colors
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Media3
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media:media:1.7.1")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Modules
    implementation(project(":core"))
    implementation(project(":auto"))
}

kapt {
    correctErrorTypes = true
}

detekt {
    config.setFrom("$rootDir/detekt.yml")
}
