import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.bol.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.heedapp.android"
        minSdk = 29
        // Google Play has required Android 16 (API 36) for new apps and updates since 31 Aug 2026.
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        create("release") {
            // Credentials read from local.properties (gitignored). The
            // keystore itself lives OUTSIDE the repo at the path stored in
            // RELEASE_STORE_FILE so it can never be picked up by `git add .`
            // even if .gitignore broke. Env-var fallback lets CI sign too
            // without checking credentials in.
            //
            // CRITICAL: losing this keystore = losing the ability to ship
            // updates to Heed on Play Store, forever. Google has no
            // recovery path for lost upload keys. Two backups (1Password
            // + offline drive) before the first AAB submission.
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
            // If storeFilePath is null, the signingConfig fields stay
            // unset and `:app:bundleRelease` fails with a clear
            // "storeFile not set" error — better than silently producing
            // an unsigned AAB. Debug builds are unaffected and use
            // Android's default debug signing.
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 enabled: shrinks classes + obfuscates + strips dead code.
            // Resource shrinking complements R8 by stripping unused
            // drawables / layouts. ProGuard rules below cover the
            // reflection-heavy and JNI-binding deps we ship.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug build — keep symbols + line numbers, no shrink.
            // The release build's R8 catches dead code before launch.
            isMinifyEnabled = false
            // Installs next to the Play build, so each keeps its own data.
            // -Pheed.demo installs a third, empty copy (store screenshots with made-up notes).
            applicationIdSuffix = if (providers.gradleProperty("heed.demo").isPresent) ".demo" else ".ondevice"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "task"
        noCompress += "litertlm"
        noCompress += "tflite"
        noCompress += "txt"   // the model vocabulary is copied out of the APK by file descriptor
        // EmbeddingGemma for search: copied out the same way
        noCompress += "onnx"
        noCompress += "onnx_data"
        noCompress += "json"
    }

    // The three models ship as install-time AI packs (Google Play: at most 1.5 GB a pack, 500 MB for the app
    // itself). Play installs them with the app and they read as the app's own assets, so ModelManager is unchanged.
    assetPacks += setOf(":task_model", ":speech_model", ":search_model")
    // A plain APK (installDebug, adb install) has no packs, so debug APKs carry the models as before.
    // -Pheed.packsOnly leaves them out, for a debug bundle that installs the way Play does (bundletool).
    if (!providers.gradleProperty("heed.packsOnly").isPresent) {
        sourceSets.getByName("debug").assets.srcDirs(
            "../task_model/src/main/assets", "../speech_model/src/main/assets", "../search_model/src/main/assets",
        )
    }

}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // LiteRT-LM (on-device LLM inference with tool calling + native audio
    // modality — Gemma 4 E2B handles both transcription and categorization
    // in production).
    implementation(libs.litertlm.android)
    // Granite Speech runs on the LiteRT interpreter, next to LiteRT-LM for the task model.
    implementation("com.google.ai.edge.litert:litert:2.2.0")
    // Search: EmbeddingGemma-300M 4-bit on ONNX Runtime, and FTS5 from a bundled SQLite (search router v2).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")

    // WorkManager (background LLM upgrade retries)
    implementation(libs.androidx.work.runtime.ktx)

    // Glance (home screen widget)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.material3)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
