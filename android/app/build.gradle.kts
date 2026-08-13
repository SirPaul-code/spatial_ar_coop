import java.net.URL
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf(File::exists)?.inputStream()?.use(::load)
}
fun secret(name: String, fallback: String) =
    (System.getenv(name) ?: localProperties.getProperty(name) ?: fallback).trim()

val arcoreApiKey = secret("ARCORE_API_KEY", "UNCONFIGURED")
val defaultServerUrl = secret("DEFAULT_SERVER_URL", "http://192.168.1.10:8080")
val defaultApiToken = secret("DEFAULT_API_TOKEN", "")
val releaseStoreFile = secret("RELEASE_STORE_FILE", "")
val releaseStorePassword = secret("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias = secret("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD", "")
val releaseSigningConfigured = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all(String::isNotBlank)

android {
    namespace = "com.sirpaul.spatialarcoop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sirpaul.spatialarcoop"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["arcoreApiKey"] = arcoreApiKey
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"${defaultServerUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEFAULT_API_TOKEN", "\"${defaultApiToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "CLOUD_ANCHORS_CONFIGURED", (arcoreApiKey != "UNCONFIGURED").toString())
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
}

data class ModelAsset(val name: String, val url: String, val sha256: String)
val requiredModels = listOf(
    ModelAsset(
        "efficientdet-lite0.tflite",
        "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite",
        "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb"
    ),
    ModelAsset(
        "efficientdet-lite2.tflite",
        "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite",
        "b3f50554cb0ea559e90328845f7d9ba4d13c8bff372914d24e06bc8bb72fa896"
    ),
    ModelAsset(
        "pose_landmarker_full.task",
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task",
        "4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad"
    )
)

fun sha256(file: File) = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

val downloadModels by tasks.registering {
    doLast {
        val assetsDir = file("src/main/assets").apply { mkdirs() }
        requiredModels.forEach { model ->
            val target = File(assetsDir, model.name)
            if (target.exists() && sha256(target).equals(model.sha256, ignoreCase = true)) return@forEach
            val temporary = File(assetsDir, "${model.name}.download").apply { delete() }
            URL(model.url).openStream().use { input -> temporary.outputStream().use(input::copyTo) }
            val actual = sha256(temporary)
            check(actual.equals(model.sha256, ignoreCase = true)) {
                "SHA-256 mismatch for ${model.name}: expected ${model.sha256} but got $actual"
            }
            if (target.exists() && !target.delete()) error("Could not replace $target")
            check(temporary.renameTo(target)) { "Could not move downloaded model to $target" }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(downloadModels)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.ar:core:1.50.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.20")
}
