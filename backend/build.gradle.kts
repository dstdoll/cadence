// Backend: the data side of Cadence. Contracts the UI depends on (api/), shared models (model/), and the
// Apple Music implementation (real/, engine/) with its native engine.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.cadence.backend"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Native engine: libcadence-native.so (the JNI NativeWrapper binds to) is built from the
    // native/cadence-native-rs Rust crate by native/build-android.sh and staged into jniLibs; no
    // CMake build is wired here.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    androidResources {
        noCompress += listOf("m4a", "mp3", "flac", "wav", "mp4")
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    // Ported engine needs document access + FileProvider
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // HLS/CDN downloads: OkHttp gives HTTP/2, an IPv4-only DNS and a real
    // call timeout (Android's HttpURLConnection wedged indefinitely on the
    // unreachable IPv6 route to the Apple CDN).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // libandroidappmusic.so is a JavaCPP build and looks up org.bytedeco.javacpp.* at load;
    // ship those classes so the lookup succeeds instead of leaving a fatal pending exception.
    implementation("org.bytedeco:javacpp:1.5.11")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.android)
}
