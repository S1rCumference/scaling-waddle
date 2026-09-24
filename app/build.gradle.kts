plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/**
 * Release signing uses a keystore committed to this repository, with a known password.
 *
 * That is a deliberate choice for a personal build: it means any checkout — or any CI run —
 * produces an APK that installs over the top of the previous one, with nothing to configure.
 * The tradeoff is that the key is not private, so anyone can build an APK Android will accept
 * as an update to this app. Fine for phones you own; replace it with a real private key before
 * handing this to anyone else. See README, "Signing".
 */
val keystoreFile: File = rootProject.file("signing/recorder.keystore")
val keystorePassword = "recorder123"
val keystoreAlias = "recorder"

android {
    namespace = "com.recorder.app"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        // ".v21" so 2.1 installs next to the stable app instead of replacing it. The code
        // namespace stays com.recorder.app; only the installed identity differs.
        applicationId = "com.recorder.app.v21"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int

        // Derived from the git tag in CI (see .github/workflows/android.yml) so the
        // in-app updater can compare versions meaningfully.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "2.1.0-dev"

        buildConfigField(
            "String",
            "RELEASE_ASSET_PREFIX",
            "\"${project.property("recorder.releaseAssetPrefix")}\"",
        )

        ndk {
            abiFilters += rootProject.extra["ndkAbi"] as String
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreFile.isFile) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
            }
        }
    }

    flavorDimensions += "form"
    productFlavors {
        create("razr") {
            dimension = "form"
            // Flip-phone build: enables the cover-screen (Phase 3) UI.
            buildConfigField("boolean", "COVER_UI_ENABLED", "true")
        }
        create("standard") {
            dimension = "form"
            // Non-flip devices (Phase 7 --no-cover-ui): cover-screen code compiled out of the flow.
            applicationIdSuffix = ".standard"
            buildConfigField("boolean", "COVER_UI_ENABLED", "false")
        }
    }

    buildTypes {
        release {
            // Kept off: both native runtimes are reached by Class.forName, and a stripped
            // build that silently loses them would look exactly like a missing model.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            // Distinct id so the development build can sit alongside the signed release
            // without an install-time signature clash.
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Native libraries are merged before abiFilters is applied, so two AARs each
            // carrying an x86 libonnxruntime.so collide even though neither would ever be
            // packaged. Drop every non-arm64 ABI up front.
            excludes += listOf(
                "lib/x86/**",
                "lib/x86_64/**",
                "lib/armeabi-v7a/**",
                "lib/armeabi/**",
                "lib/mips/**",
                "lib/mips64/**",
            )
        }
    }

    lint {
        // Lint Vital is on by default for release builds and needs a "local lint" AAR from
        // every dependency for cross-module analysis. AGP refuses to build one for core-asr
        // or core-llm, which each depend directly on a local .aar file (sherpa-onnx,
        // llama.cpp) rather than a Maven artifact, since neither ships to a repository:
        // ":core-asr:bundleReleaseLocalLintAar ... Direct local .aar file dependencies are
        // not supported when building an AAR." That failed a release build before it
        // compiled anything release-specific, over a lint pass this project doesn't
        // otherwise run. Debug builds were never affected because Lint Vital only runs on
        // release.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core-audio"))
    implementation(project(":core-asr"))
    implementation(project(":core-storage"))
    implementation(project(":core-llm"))
    implementation(project(":core-connectors"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The sherpa ASR models ship as tar.bz2 and the JDK has no bzip2 decoder.
    implementation("org.apache.commons:commons-compress:1.27.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws in unit tests; this supplies a real one.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
