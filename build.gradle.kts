plugins {
    // AGP 8.5 caps out at API 34; compileSdk 35 needs 8.6+. 8.7.x pairs with Gradle 8.9,
    // which is what the wrapper already pins.
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

extra["compileSdkVersion"] = 35
extra["minSdkVersion"] = 26
extra["targetSdkVersion"] = 35

// Only arm64 is shipped. Both native runtimes (sherpa-onnx ~24 MB, llama.cpp) carry a
// per-ABI copy, so shipping four ABIs would quadruple an already large APK for hardware
// nobody in this fleet owns.
extra["ndkAbi"] = "arm64-v8a"

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
