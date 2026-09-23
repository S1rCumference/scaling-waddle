plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/**
 * Release signing comes from the environment so the key never lives in the repo.
 * CI decodes KEYSTORE_BASE64 to a file and exports these; locally they are simply absent
 * and the release build stays unsigned rather than failing.
 */
val keystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { File(it).isFile }

android {
    namespace = "com.recorder.app"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        applicationId = "com.recorder.app"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int

        // Derived from the git tag in CI (see .github/workflows/android.yml) so the
        // in-app updater can compare versions meaningfully.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"

        ndk {
            abiFilters += rootProject.extra["ndkAbi"] as String
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
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
            if (keystorePath != null) {
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

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
