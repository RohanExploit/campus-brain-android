import java.util.Properties

// AGP 9 has built-in Kotlin support; applying org.jetbrains.kotlin.android
// on top of it is now an error, not just redundant.
plugins {
    id("com.android.application")
}

// Untracked, and deliberately so: it holds the upload key password. Absent on a
// fresh clone or CI box, which is why every use of it below is guarded.
val keystorePropertiesFile = rootProject.file("keystore.properties")

android {
    namespace = "com.campusbrain.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.campusbrain.app"
        // Distinct from com.companybrain.company_brain, so this app gets its own
        // /sdcard/Android/data/<pkg>/files dir and cannot collide with the
        // Flutter app's bundle.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The demo device (vivo I2501, Android 16) is arm64-v8a. Restricting
            // ABIs keeps the bundled SQLite and ONNX Runtime native payloads to
            // one architecture. NOTE: an x86_64 emulator cannot install this
            // build; add "x86_64" here if you need emulator testing.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Created only when the properties file is present. Declaring a release
        // config with a null storeFile would configure fine and then fail deep
        // inside the packaging task with an unhelpful message.
        if (keystorePropertiesFile.exists()) {
            val keystoreProperties = Properties().apply {
                keystorePropertiesFile.inputStream().use { load(it) }
            }
            create("release") {
                // Relative in the properties file on purpose: java.util.Properties
                // treats a backslash as an escape, so a literal Windows path there
                // would be silently mangled.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: ONNX Runtime and the bundled SQLite both
            // reach for classes reflectively, and shrinking them is a separate
            // job with its own keep rules.
            isMinifyEnabled = false
            // Falls back to debug signing so a machine without the upload key can
            // still produce an installable release build.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // brain.db is opened directly out of assets on first run and the ONNX
        // graph is memory-mapped; compressing either just costs a decompress.
        noCompress += listOf("db", "onnx")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // NOT the platform SQLite. Measured on the demo device (vivo I2501,
    // Android 16, SQLite 3.44.3): `chunks_fts MATCH` fails with
    // "no such module: fts5". Android's AOSP build enables FTS3/FTS4 and not
    // FTS5, which is also why Room ships @Fts3/@Fts4 and no @Fts5. This
    // artifact compiles SQLite from source into the APK with FTS5 on, so the
    // bundle behaves the same everywhere and matches the SQLite 3.35.5 that
    // wrote it.
    implementation("androidx.sqlite:sqlite-bundled:2.5.1")
    implementation("androidx.sqlite:sqlite:2.5.1")

    // Runs the exact all-MiniLM-L6-v2 graph that embedded the corpus. A query
    // vector from any other model is not comparable to the stored ones, which
    // rules out MediaPipe TextEmbedder (different model, 100d, different space).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests. Without it android.jar ships a stub
    // whose methods throw, so every JSONObject parse silently became null
    // inside runCatching and CloudAnswerTest failed for a reason unrelated
    // to the code under test.
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.sqlite:sqlite-bundled:2.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
