plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    kotlin("kapt")
}

android {
    namespace = "net.asksakis.massdroidv2.core"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // 16 KB page-size ELF alignment: not the default on NDK r27 (default from r28).
                // Required for Android 16 KB-page devices and Play targetSdk 35+ uploads.
                arguments("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        prefab = true
    }

    testOptions {
        // Most :core classes `import android.util.Log`; returning default values
        // for unmocked android.jar stubs lets pure-logic JVM unit tests touch them
        // without a "Method ... not mocked" crash (no Robolectric needed).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose graphics type only (ProviderManifestCache caches rendered icons as
    // ImageBitmap). No compose compiler / @Composable here; both front-ends
    // (phone + TV) are Compose, so the shared icon type belongs in core.
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    api(composeBom)
    api("androidx.compose.ui:ui-graphics")

    // Exposed to consumers (repositories expose Flow/models; app + atv use these)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("androidx.core:core-ktx:1.16.0")

    // Internal to core
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.google.oboe:oboe:1.10.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-compiler:2.52")

    // Unit tests (pure-JVM, no Robolectric). Kotlin/coroutines versions match the
    // api() deps above; deps kept inline as the project has no version catalog.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.12")
}

kapt {
    correctErrorTypes = true
}

detekt {
    config.setFrom("$rootDir/detekt.yml")
}
