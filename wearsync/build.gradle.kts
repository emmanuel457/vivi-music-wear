plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.music.vivi.wearsync"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Exposed as `api` because both :app and :wear serialize/deserialize these DTOs.
    api(libs.kotlinx.serialization.json)
    api(libs.play.services.wearable)
    // Task<T>.await(), used on both sides to drive the Data Layer from coroutines.
    api(libs.coroutines.play.services)
}
