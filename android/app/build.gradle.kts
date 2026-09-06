import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// DEV-ONLY signing material. The encoded PKCS12 key is intentionally committed
// so every GitHub Actions runner and local clone signs Spatial Sync with the same
// certificate. Never reuse this key for a production/Play Store application.
val stableDevStoreSource = rootProject.file("keystore/spatial-sync-dev.p12.b64")
val stableDevStore = layout.buildDirectory.file("generated/signing/spatial-sync-dev.p12").get().asFile
if (!stableDevStore.exists()) {
    require(stableDevStoreSource.isFile) { "Missing stable development signing material" }
    stableDevStore.parentFile.mkdirs()
    stableDevStore.writeBytes(Base64.getMimeDecoder().decode(stableDevStoreSource.readText().trim()))
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val ciRunAttempt = System.getenv("GITHUB_RUN_ATTEMPT")?.toIntOrNull() ?: 0
val generatedVersionCode = if (ciRunNumber != null) {
    400_000 + ciRunNumber * 10 + ciRunAttempt
} else {
    400_000
}
val generatedVersionName = if (ciRunNumber != null) {
    "0.4.0-dev.${ciRunNumber}.${ciRunAttempt}"
} else {
    "0.4.0-dev.local"
}

android {
    namespace = "com.sirpaul.spatialnomap"
    compileSdk = 36

    signingConfigs {
        create("stableDev") {
            storeFile = stableDevStore
            storePassword = "spatialdevpass"
            keyAlias = "spatial-dev"
            keyPassword = "spatialdevpass"
        }
    }

    defaultConfig {
        applicationId = "com.sirpaul.spatialnomap"
        minSdk = 33
        targetSdk = 36
        versionCode = generatedVersionCode
        versionName = generatedVersionName
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDev")
        }
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
    implementation("com.google.ar:core:1.56.0")
    implementation("org.opencv:opencv:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
