import java.net.URI
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Stable sideload/update signing for this POC branch. The encoded PKCS12 key is
// intentionally committed so CI and local builds use the same certificate.
// Never reuse this key for a Play Store / production signing identity.
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
    500_000 + ciRunNumber * 10 + ciRunAttempt
} else {
    500_000
}
val generatedVersionName = if (ciRunNumber != null) {
    "0.6.0-vehicles.${ciRunNumber}.${ciRunAttempt}"
} else {
    "0.6.0-vehicles.local"
}

// EfficientDet-Lite0 is downloaded at build time and then packaged as a normal
// local Android asset. Runtime inference is fully on-device; the app does not need
// internet access for vehicle detection. Keeping the model out of git avoids a
// multi-megabyte binary blob while still producing a self-contained APK.
val generatedMlAssetsDir = layout.buildDirectory.dir("generated/ml-assets").get().asFile
val vehicleModelFile = generatedMlAssetsDir.resolve("efficientdet_lite0_uint8.tflite")
val downloadVehicleModel = tasks.register("downloadVehicleModel") {
    outputs.file(vehicleModelFile)
    doLast {
        if (!vehicleModelFile.isFile || vehicleModelFile.length() < 1_000_000L) {
            vehicleModelFile.parentFile.mkdirs()
            val tmp = vehicleModelFile.resolveSibling("${vehicleModelFile.name}.tmp")
            URI("https://storage.googleapis.com/mediapipe-tasks/object_detector/efficientdet_lite0_uint8.tflite")
                .toURL()
                .openStream()
                .use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            require(tmp.length() >= 1_000_000L) { "Downloaded vehicle model is unexpectedly small" }
            if (vehicleModelFile.exists()) vehicleModelFile.delete()
            require(tmp.renameTo(vehicleModelFile)) { "Could not install downloaded vehicle model" }
        }
    }
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
        getByName("release") {
            signingConfig = signingConfigs.getByName("stableDev")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    sourceSets.getByName("main").assets.srcDir(generatedMlAssetsDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild").configure { dependsOn(downloadVehicleModel) }

dependencies {
    implementation("com.google.ar:core:1.56.0")
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    testImplementation("junit:junit:4.13.2")
}
