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
extra["coroutinesVersion"] = "1.9.0"

/*
 * ...and 1.9.0 exactly, not the newest, because of the Kotlin standard library underneath.
 *
 * This project compiles with Kotlin 1.9.24, whose compiler reads class metadata only up to
 * version 2.0.0. Coroutines 1.10.x depends on kotlin-stdlib 2.1.0 and every module then
 * fails to compile on kotlin.Unit. 1.9.0 is the first release carrying the
 * limitedParallelism bridge the llama.cpp AAR calls and the last whose stdlib this
 * compiler can read.
 */
extra["stdlibVersion"] = "2.0.20"

/*
 * The library that is *compiled against* and the one that is *shipped* are deliberately
 * different versions.
 *
 * The llama.cpp AAR is built with Kotlin 2.3, and Kotlin 2.1 began emitting calls to
 * kotlin.coroutines.jvm.internal.SpillingKt from the code it generates for suspend
 * functions. That class does not exist in the 2.0.20 stdlib, so the AAR loaded, started
 * generating, and died with "Failed resolution of: Lkotlin/coroutines/jvm/internal/
 * SpillingKt;" — and because that is an Error rather than an Exception, the wrapper's own
 * catch missed it and left its engine wedged in Generating for the life of the process.
 *
 * So: compile against 2.0.20, whose metadata this compiler can read, and package 2.3.0,
 * which has everything the AAR's generated code calls. The stdlib keeps strict backward
 * binary compatibility, so 1.9-compiled code runs on it unchanged.
 *
 * This is a stopgap and it is worth naming as one. Moving the project to Kotlin 2.x makes
 * both numbers the same again, and that is its own piece of work.
 */
extra["runtimeStdlibVersion"] = "2.3.0"

subprojects {
    configurations.configureEach {
        // Anything the compiler or an annotation processor reads metadata from gets the
        // version it can parse; anything that ends up in the APK gets the newer one.
        val readByTheCompiler = name.endsWith("CompileClasspath") ||
            name.startsWith("ksp") ||
            name.contains("kotlinCompiler", ignoreCase = true) ||
            name.startsWith("lint")
        val stdlib = if (readByTheCompiler) {
            rootProject.extra["stdlibVersion"]
        } else {
            rootProject.extra["runtimeStdlibVersion"]
        }
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:$stdlib")
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
