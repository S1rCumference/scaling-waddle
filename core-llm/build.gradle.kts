plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// No llama.cpp, no AAR from libs/, no second source set, no metadata-version override. Generation
// happens on a hosted model now, so what is left of this module is prompt building, answer
// parsing and the provider presets — all pure Kotlin, all unit tested without a network.

android {
    namespace = "com.recorder.core.llm"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
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
    api(project(":core-storage"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${rootProject.extra["coroutinesVersion"]}")

    testImplementation("junit:junit:4.13.2")
}
