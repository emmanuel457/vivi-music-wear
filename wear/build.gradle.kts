import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.music.vivi.wear"
    compileSdk = 37

    defaultConfig {
        // Must match :app — the Wearable Data Layer only pairs a watch app to a
        // phone app when the application IDs and signing certificates match.
        applicationId = "com.vivi.vivimusic"

        // Wear OS 4 (API 33) and newer. Galaxy Watch 4+ / Pixel Watch.
        minSdk = 33
        targetSdk = 36

        // Must stay >= the phone versionCode so the Play/adb pairing logic never
        // considers the watch build a downgrade.
        versionCode = 74
        versionName = "6.0.5-wear"

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // Phone and watch APKs are signed with this same keystore. Without a
        // signature match, MessageClient/DataClient silently deliver nothing.
        create("shared") {
            storeFile = rootProject.file("keystore/vivi-wear.keystore")
            storePassword = System.getenv("VIVI_STORE_PASSWORD") ?: "viviwear"
            keyAlias = System.getenv("VIVI_KEY_ALIAS") ?: "vivi"
            keyPassword = System.getenv("VIVI_KEY_PASSWORD") ?: "viviwear"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
        checkDependencies = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/*.md"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation(project(":innertube"))
    implementation(project(":wearsync"))

    // Wear OS
    implementation(libs.wear)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.tooling.preview)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.play.services.wearable)

    // Compose
    implementation(libs.activity)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)
    implementation(libs.androidx.core.splashscreen)
    // Wear Compose ships no icon set of its own. material-icons was frozen at
    // 1.7.8 upstream, so it is pinned rather than tracking libs.versions.compose.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // RemoteInput-based text entry: the only sane way to type on a watch.
    implementation("androidx.wear:wear-input:1.1.0")

    // Playback
    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    // Images
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    // Album-art colour extraction for the dynamic theme.
    implementation(libs.palette)

    implementation(libs.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    coreLibraryDesugaring(libs.desugaring)
}
