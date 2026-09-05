plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sirpaul.spatialnomap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sirpaul.spatialnomap"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.ar:core:1.54.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
