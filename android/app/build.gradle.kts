plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sirpaul.spatialnomap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sirpaul.spatialnomap"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.google.ar:core:1.54.0")
    implementation("org.opencv:opencv:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
