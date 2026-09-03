// AGP 9 has built-in Kotlin support; applying org.jetbrains.kotlin.android
// on top of it is now an error, not just redundant.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.kriet.campusbrain"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kriet.campusbrain"
        // Distinct from com.companybrain.company_brain, so this app gets its own
        // /sdcard/Android/data/<pkg>/files dir and cannot collide with the
        // Flutter app's bundle.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        ndk {
            // The demo device (vivo I2501, Android 16) is arm64-v8a. Restricting
            // ABIs keeps the bundled SQLite and ONNX Runtime native payloads to
            // one architecture. NOTE: an x86_64 emulator cannot install this
            // build; add "x86_64" here if you need emulator testing.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.sqlite:sqlite-bundled:2.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // CloudAnswer.parseConfig/findConfigFile use org.json.JSONObject. Local unit
    // tests run against the Android platform's stub android.jar, whose
    // org.json methods throw "not mocked" rather than working -- silently
    // caught by parseConfig's runCatching, so every CloudAnswerTest case
    // observed that as parseConfig always returning null. This reference
    // implementation (same org.json package) takes precedence on the unit
    // test classpath and actually works.
    testImplementation("org.json:json:20260814")
}
