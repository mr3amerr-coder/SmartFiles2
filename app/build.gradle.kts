plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.smartfiles"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.smartfiles"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "1.5"
    }

    // One fixed key for every build, so a new APK installs over the old one instead of
    // failing with "App not installed". (Personal sideload key; not for the Play Store.)
    signingConfigs {
        create("smart") {
            storeFile = file("smartfiles.jks")
            storePassword = "smartfiles"
            keyAlias = "smartfiles"
            keyPassword = "smartfiles"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("smart") }
        release {
            // R8 shrinks code and resources: a much smaller, faster-starting APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("smart")
        }
    }

    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin") } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
