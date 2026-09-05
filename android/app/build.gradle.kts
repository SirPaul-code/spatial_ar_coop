plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sirpaul.spatialnomap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sirpaul.spatialnomap"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.ar:core:1.54.0")
    implementation("org.opencv:opencv:4.12.0")
}
