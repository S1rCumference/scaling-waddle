plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// sherpa-onnx ships as an AAR on its GitHub releases rather than to Maven Central.
// Drop it in core-asr/libs/ (see scripts/fetch_models.sh) and the Parakeet engine
// compiles in automatically; without it the module still builds and the app falls back
// to a no-op engine that records audio but produces no text.
val sherpaAar = fileTree("libs") { include("sherpa-onnx*.aar", "sherpa-onnx*.jar") }
val sherpaPresent = !sherpaAar.isEmpty

android {
    namespace = "com.recorder.core.asr"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        buildConfigField("boolean", "SHERPA_AVAILABLE", sherpaPresent.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        *(if (sherpaPresent) arrayOf("src/sherpa/java") else emptyArray()),
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-audio"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    if (sherpaPresent) implementation(sherpaAar)

    testImplementation("junit:junit:4.13.2")
}
