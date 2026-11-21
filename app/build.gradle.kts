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
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("cadence.versionCode").orNull?.toInt() ?: 13
        versionName = providers.gradleProperty("cadence.versionName").orNull ?: "0.2.0-dev.5"
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

// Un release no puede llevar la sesion de pruebas. `assets/native-data/` y `assets/engine/`
// traen el media-user-token, el developer token, el ANDROID_ID, el estado de FairPlay y el
// mpl_db entero con las cookies de la cuenta: cualquiera con el APK firmado entra a ella.
//
// El .gitignore los deja fuera del repositorio, pero eso no protege un release construido en
// una maquina que si los tiene -- los empaqueta en silencio, y el APK se ve normal. Por eso
// la comprobacion vive en el build y no en la disciplina de quien compila.
//
// Para un release local que se queda en el dispositivo propio: CADENCE_ALLOW_SEEDED_RELEASE=1.
val rutasDeCredenciales = listOf(
    rootProject.file("backend/src/main/assets/native-data"),
    rootProject.file("backend/src/main/assets/engine"),
)
val permitirSembrado = System.getenv("CADENCE_ALLOW_SEEDED_RELEASE") == "1"
val comprobarCredenciales = tasks.register("checkNoSeededCredentials") {
    group = "verification"
    description = "Falla si un release llevaria las credenciales de prueba sembradas en assets."
    val presentes = rutasDeCredenciales.filter { it.exists() }.map { it.relativeTo(rootProject.projectDir).path }
    val permitido = permitirSembrado
    doLast {
        if (presentes.isEmpty()) return@doLast
        if (permitido) {
            logger.warn("AVISO: este release lleva credenciales en vivo (${presentes.joinToString()}). " +
                "CADENCE_ALLOW_SEEDED_RELEASE=1 esta puesta. No distribuyas este APK.")
            return@doLast
        }
        throw GradleException(
            "Este release llevaria credenciales en vivo:\n" +
                presentes.joinToString("\n") { "  - $it" } +
                "\n\nSon la sesion de Apple Music de una cuenta real (media-user-token, developer " +
                "token, ANDROID_ID, estado de FairPlay y el mpl_db con sus cookies). Quien tenga el " +
                "APK firmado entra a esa cuenta.\n\nSaca esas carpetas antes de compilar un release, " +
                "o pon CADENCE_ALLOW_SEEDED_RELEASE=1 si el APK se queda en tu propio dispositivo."
        )
    }
}
tasks.matching { it.name == "mergeReleaseAssets" || it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(comprobarCredenciales) }
