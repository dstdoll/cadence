import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val compressLibs = providers.gradleProperty("cadence.compressLibs").orNull == "true"

android {
    namespace = "app.cadence"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.cadence"
        minSdk = 31
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["extractNativeLibs"] = compressLibs.toString()
        // The native engine only exists for arm64; without this the FFmpeg decoder would also
        // ship its x86 and 32-bit ARM builds (about 30 MB the app could never load).
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing comes from a properties file kept outside the repository: by default
    // ~/.config/cadence/signing.properties (or the path in CADENCE_SIGNING), with storeFile,
    // storePassword, keyAlias and keyPassword. Without it release builds use the debug key, so the
    // project still builds on any machine.
    val signing = Properties().apply {
        val f = file(System.getenv("CADENCE_SIGNING") ?: "${System.getProperty("user.home")}/.config/cadence/signing.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (!signing.isEmpty) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // No git revision inside the APK: it would tie every build to a commit.
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    // The native wrapper + Apple StoreServices stack was built and
    // validated with extracted libraries (the old app sets the same flags).
    // Loading them from inside the APK changes the library paths the native
    // CKC/GetPersistentKey path observes, which is where the KD dies.
    packaging {
        // -Pcadence.compressLibs=true stores the .so files compressed (about 40 MB less in the
        // APK); Android then extracts them at install time. Off by default: it changes where
        // the native stack is loaded from, so it needs a real download test before shipping.
        jniLibs { useLegacyPackaging = compressLibs }
    }
    androidResources {
        // The UI ships English + Spanish; keep only those locales from androidx too.
        localeFilters += setOf("en", "es")
        noCompress += listOf("m4a", "mp3", "flac", "wav", "mp4")
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    // The frontend only talks to the backend through app.cadence.backend.api interfaces.
    implementation(project(":backend"))

    implementation(libs.androidx.core.ktx)
    // Per-app language: AppCompatDelegate.setApplicationLocales backports to minSdk (framework
    // LocaleManager is API 33+); works with a ComponentActivity, no AppCompatActivity needed.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navsuite)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.materialkolor)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui.compose)
    // Installs the baseline profiles Compose/Media3 ship, so release builds start AOT-compiled instead of interpreted.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
}
