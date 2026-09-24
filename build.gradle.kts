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

/*
 * Must be 1.9.0 or newer, and it is not a preference.
 *
 * The bundled llama.cpp AAR (ARM's AiChat wrapper) calls
 * `Dispatchers.IO.limitedParallelism(1)`. In coroutines 1.9.0 that method gained an
 * optional `name` parameter, which changed the synthetic default-argument bridge the
 * compiled AAR now references. Against 1.8.1 that bridge does not exist, so constructing
 * the inference engine threw NoSuchMethodError and *every* local model failed to load —
 * on the phone it read "could not load qwen3-1.7b-q4.gguf: No static method
 * limitedParallelism$default(...)".
 */
extra["coroutinesVersion"] = "1.10.2"

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
