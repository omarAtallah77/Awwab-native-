plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.awwab"
    compileSdk = 36
    ndkVersion = "25.2.9519653"
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    defaultConfig {
        applicationId = "com.example.awwab"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    // Jetpack + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.material3)
    implementation(libs.androidx.compiler)

    // remove ML Kit pose detection (conflicts)
    // implementation(libs.pose.detection.common)
    // implementation(libs.pose.detection)

    // remove identity services (not needed)
    // implementation(libs.identity.doctypes.jvm)

    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // TFLite runtime
    implementation("org.tensorflow:tensorflow-lite:2.13.0") // or latest stable
    implementation("org.tensorflow:tensorflow-lite-support:0.4.3") // for FileUtil, etc.
    // Edge LiteRT (YOLOv8 TFLite runtime)
  //  implementation("com.google.ai.edge.litert:litert-api:1.4.1")
    //implementation("com.google.ai.edge.litert:litert-support-api:1.4.1")

    // MediaPipe Pose Landmarker
    implementation("com.google.mediapipe:tasks-vision:0.10.18")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
