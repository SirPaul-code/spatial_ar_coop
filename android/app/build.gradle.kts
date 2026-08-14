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
        versionCode = 13
        versionName = "1.2.0"
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
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        noCompress += "tflite"
        noCompress += "task"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

data class VerifiedModel(
    val assetName: String,
    val url: String,
    val bytes: Long,
    val sha256: String
)

val objectDetectorFastModel = VerifiedModel(
    assetName = "efficientdet-lite0.tflite",
    url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite",
    bytes = 4_602_795L,
    sha256 = "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb"
)
val objectDetectorModel = VerifiedModel(
    assetName = "efficientdet-lite2.tflite",
    url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite",
    bytes = 7_515_971L,
    sha256 = "b3f50554cb0ea559e90328845f7d9ba4d13c8bff372914d24e06bc8bb72fa896"
)
val poseLandmarkerModel = VerifiedModel(
    assetName = "pose_landmarker_full.task",
    url = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task",
    bytes = 9_398_198L,
    sha256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun registerVerifiedModelTask(taskName: String, model: VerifiedModel) = tasks.register(taskName) {
    val modelFile = layout.projectDirectory.file("src/main/assets/${model.assetName}").asFile
    outputs.file(modelFile)
    doLast {
        if (
            modelFile.exists() &&
            modelFile.length() == model.bytes &&
            sha256(modelFile) == model.sha256
        ) return@doLast
        modelFile.parentFile.mkdirs()
        val temporary = modelFile.resolveSibling("${modelFile.name}.download")
        temporary.delete()
        URL(model.url).openStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
        val actualBytes = temporary.length()
        val actualSha256 = sha256(temporary)
        check(actualBytes == model.bytes) {
            "Downloaded ${model.assetName} size $actualBytes did not match expected ${model.bytes}"
        }
        check(actualSha256 == model.sha256) {
            "Downloaded ${model.assetName} checksum mismatch: expected ${model.sha256}, got $actualSha256"
        }
        if (modelFile.exists()) modelFile.delete()
        check(temporary.renameTo(modelFile)) { "Could not move downloaded ${model.assetName} into assets" }
    }
}

val downloadObjectDetectorFastModel = registerVerifiedModelTask("downloadObjectDetectorFastModel", objectDetectorFastModel)
val downloadObjectDetectorModel = registerVerifiedModelTask("downloadObjectDetectorModel", objectDetectorModel)
val downloadPoseLandmarkerModel = registerVerifiedModelTask("downloadPoseLandmarkerModel", poseLandmarkerModel)

tasks.named("preBuild").configure {
    dependsOn(downloadObjectDetectorFastModel, downloadObjectDetectorModel, downloadPoseLandmarkerModel)
}

dependencies {
    implementation("com.google.ar:core:1.54.0")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.4")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("com.google.android.material:material:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
