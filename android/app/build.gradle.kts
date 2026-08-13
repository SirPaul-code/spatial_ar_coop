import java.net.URL
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun secret(name: String, fallback: String): String =
    (System.getenv(name) ?: localProperties.getProperty(name) ?: fallback).trim()

val arcoreApiKey = secret("ARCORE_API_KEY", "UNCONFIGURED")
val defaultServerUrl = secret("DEFAULT_SERVER_URL", "http://192.168.1.10:8080")
val defaultApiToken = secret("DEFAULT_API_TOKEN", "")
val releaseStoreFile = secret("RELEASE_STORE_FILE", "")
val releaseStorePassword = secret("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias = secret("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD", "")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all(String::isNotBlank)

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

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

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

val requiredModels = listOf(
    Triple(
        "efficientdet-lite0.tflite",
        "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite",
        "df3d841163e688f11274618b03a1be2036815955b20ee785b19d29f05493ae82"
    ),
    Triple(
        "efficientdet-lite2.tflite",
        "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite",
        "428fde2ea6759f76defd638a2044096b84d4d84fca4c629a9c1bd07f81560abe"
    ),
    Triple(
        "pose_landmarker_full.task",
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task",
        "33c0c672e735b90498323b11b312a457394b45d649c3b52d776ecc950322fae8"
    )
)

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

val downloadModels by tasks.registering {
    doLast {
        val assetsDir = file("src/main/assets").apply { mkdirs() }
        requiredModels.forEach { (name, url, expectedSha) ->
            val target = File(assetsDir, name)
            if (target.exists() && sha256(target).equals(expectedSha, ignoreCase = true)) return@forEach
            val temporary = File(assetsDir, "$name.download")
            temporary.delete()
            URL(url).openStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            val actualSha = sha256(temporary)
            check(actualSha.equals(expectedSha, ignoreCase = true)) {
                "SHA-256 mismatch for $name: expected $expectedSha but got $actualSha"
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
