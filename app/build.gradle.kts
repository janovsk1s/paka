plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.paka.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paka.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.9.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so the release APK is installable via sideload.
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Barcode generation — Apache-2.0 ZXing, no Google Play Services.
    implementation("com.google.zxing:core:3.5.3")
    // GS1 DataBar generation — OkapiBarcode, pure Java (Zint port), no native, no Google.
    implementation("uk.org.okapibarcode:okapibarcode:0.5.1")

    // Scanning — zxing-cpp (no Google Play Services) + CameraX.
    implementation("io.github.zxing-cpp:android:2.3.0")
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
}
