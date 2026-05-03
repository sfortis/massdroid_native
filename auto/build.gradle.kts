plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "net.asksakis.massdroidv2.auto"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media3:media3-session:1.6.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
}

detekt {
    config.setFrom("$rootDir/detekt.yml")
}
