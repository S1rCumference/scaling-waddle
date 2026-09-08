plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// llama.cpp's Android bindings are not on Maven Central. Build the AAR from
// llama.cpp/examples/llama.android (see scripts/fetch_models.sh --llama) and drop it in
// core-llm/libs/; the local-model provider then compiles in with no other change.
val llamaAar = fileTree("libs") { include("llama*.aar", "llama*.jar") }
val llamaPresent = !llamaAar.isEmpty

android {
    namespace = "com.recorder.core.llm"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        buildConfigField("boolean", "LLAMA_AVAILABLE", llamaPresent.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        *(if (llamaPresent) arrayOf("src/llama/java") else emptyArray()),
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
    api(project(":core-storage"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    if (llamaPresent) implementation(llamaAar)

    testImplementation("junit:junit:4.13.2")
}
