plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.adesso.agenticaifunctioncalling"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "de.adesso.agenticaifunctioncalling"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only ship ARM64 to keep APK lean
        ndk { abiFilters.addAll(listOf("arm64-v8a")) }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Ensure native libraries from LiteRT-LM are included
        jniLibs {
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
        }
    }
}

dependencies {
    // ── Compose BOM ───────────────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ── LiteRT-LM (latest stable) ─────────────────────────────────────────────
    // Replaces deprecated MediaPipe tasks-genai. Uses .litertlm model format.
    // https://github.com/google-ai-edge/LiteRT-LM
    // Include the android classifier to get native .so files
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release") {
        // Ensure native libraries are included
        artifact {
            name = "litertlm-android"
            type = "aar"
        }
    }

    // Native GPU/OpenCL libraries referenced in AndroidManifest
    // (no extra Gradle dep needed; declared via <uses-native-library>)

    // ── Koin ─────────────────────────────────────────────────────────────────
    val koinBom = platform("io.insert-koin:koin-bom:4.0.4")
    implementation(koinBom)
    implementation("io.insert-koin:koin-android")
    implementation("io.insert-koin:koin-androidx-compose")

    // ── Ktor (model download) ─────────────────────────────────────────────────
    val ktorVersion = "3.1.3"
    implementation("io.ktor:ktor-client-android:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // ── Coroutines ────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ── DataStore (persist model-download state) ──────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation(libs.kotlinx.serialization.json)
}
    
//    buildTypes {
//        release {
//            isMinifyEnabled = false
//            proguardFiles(
//                getDefaultProguardFile("proguard-android-optimize.txt"),
//                "proguard-rules.pro"
//            )
//        }
//    }
//    compileOptions {
//        sourceCompatibility = JavaVersion.VERSION_11
//        targetCompatibility = JavaVersion.VERSION_11
//    }
//    buildFeatures {
//        compose = true
//    }
//}
//
//dependencies {
//    implementation(libs.androidx.core.ktx)
//    implementation(libs.androidx.lifecycle.runtime.ktx)
//    implementation(libs.androidx.activity.compose)
//    implementation(platform(libs.androidx.compose.bom))
//    implementation(libs.androidx.compose.ui)
//    implementation(libs.androidx.compose.ui.graphics)
//    implementation(libs.androidx.compose.ui.tooling.preview)
//    implementation(libs.androidx.compose.material3)
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.androidx.junit)
//    androidTestImplementation(libs.androidx.espresso.core)
//    androidTestImplementation(platform(libs.androidx.compose.bom))
//    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
//    debugImplementation(libs.androidx.compose.ui.tooling)
//    debugImplementation(libs.androidx.compose.ui.test.manifest)
//}