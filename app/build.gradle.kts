plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.audiosub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.audiosub"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            // sherpa-onnx and onnxruntime-android both bundle libonnxruntime.so.
            // Keep sherpa-onnx's version (already in jniLibs/arm64-v8a/) by picking first.
            pickFirst("**/libonnxruntime.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.workmanager)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.recyclerview)
    // sherpa-onnx: local JAR (Java API) + .so files in jniLibs/arm64-v8a/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    // ONNX Runtime Java API for NLLB inference (native .so already provided by sherpa-onnx)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
}
