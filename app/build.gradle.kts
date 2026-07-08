import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) signingPropertiesFile.inputStream().use(::load)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.paka.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.paka.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 50
        versionName = "0.15.0"
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".photopreview"
            versionNameSuffix = "-photo-beta.3.2"
            resValue("string", "app_name", "Paka Photo Test")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Shareable preview channel: minified like release so the APK stays
        // release-sized, but debug-signed under a suffixed id so it installs
        // alongside the real app without the release keystore.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".photopreview"
            versionNameSuffix = "-photo-beta.3.2"
            resValue("string", "app_name", "Paka Photo Test")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
        // AGP 9 disables resource values by default; the debug and preview
        // channels rely on them for their side-by-side app names.
        resValues = true
    }
    testOptions {
        unitTests {
            // Robolectric drives the store recovery tests.
            isIncludeAndroidResources = true
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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

    // Barcode generation — Apache-2.0 ZXing, no Google Play Services.
    implementation(libs.zxing.core)
    // GS1 DataBar generation — OkapiBarcode, pure Java (Zint port), no native, no Google.
    implementation(libs.okapibarcode)

    // Scanning — zxing-cpp (no Google Play Services) + CameraX.
    implementation(libs.zxing.cpp)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Installs the merged baseline profile on sideloaded APKs, where no
    // app store delivers ahead-of-time compilation profiles.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
