import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

val xfeatRevision = "bd421aad1ce6d25dc172cd9579cc13b9da21356f"
val xfeatSha256 = "6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df"
val xfeatBytes = 1_414_480L
val xfeatAssetDir = layout.buildDirectory.dir("generated/xfeat-assets")
val xfeatModelFile = xfeatAssetDir.map { it.file("models/xfeat.tflite") }

fun stableArSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun stableArVerifiedXFeat(file: File): Boolean =
    file.isFile && file.length() == xfeatBytes && stableArSha256(file) == xfeatSha256

fun stableArDownload(url: String, destination: File, attempts: Int): Boolean {
    var last: Throwable? = null
    repeat(attempts) { index ->
        try {
            destination.delete()
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "StableAR-build/$xfeatRevision")
            connection.setRequestProperty("Accept", "application/octet-stream,*/*")
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            return true
        } catch (t: Throwable) {
            last = t
            destination.delete()
            if (index + 1 < attempts) Thread.sleep((1L shl index.coerceAtMost(5)) * 1_000L)
        }
    }
    logger.warn("StableAR XFeat download failed from $url after $attempts attempts: ${last?.message}")
    return false
}

val prepareXFeatModel by tasks.registering {
    outputs.file(xfeatModelFile)
    doLast {
        val output = xfeatModelFile.get().asFile
        output.parentFile.mkdirs()
        if (stableArVerifiedXFeat(output)) return@doLast

        val tmp = File(output.parentFile, "${output.name}.tmp")
        fun install(candidate: File): Boolean {
            if (!stableArVerifiedXFeat(candidate)) return false
            tmp.delete()
            candidate.inputStream().buffered().use { input ->
                tmp.outputStream().buffered().use { outputStream -> input.copyTo(outputStream) }
            }
            check(stableArVerifiedXFeat(tmp)) { "Pinned XFeat model changed while copying into build assets" }
            output.delete()
            check(tmp.renameTo(output)) { "Could not install verified XFeat model" }
            return true
        }

        val explicit = sequenceOf(
            System.getenv("STABLEAR_XFEAT_MODEL"),
            providers.gradleProperty("stablearXFeatModel").orNull
        ).filterNotNull().map(String::trim).filter(String::isNotEmpty)
            .map(::File).firstOrNull(::stableArVerifiedXFeat)
        if (explicit != null && install(explicit)) {
            logger.lifecycle("StableAR: using verified XFeat model from ${explicit.absolutePath}")
            return@doLast
        }

        val direct = File(output.parentFile, "xfeat.download")
        val upstream = "https://huggingface.co/litert-community/xfeat-litert/resolve/$xfeatRevision/xfeat.tflite?download=true"
        if (stableArDownload(upstream, direct, attempts = 5) && install(direct)) {
            direct.delete()
            logger.lifecycle("StableAR: downloaded and verified pinned XFeat model from upstream")
            return@doLast
        }
        direct.delete()

        // Hugging Face/Xet occasionally rate-limits anonymous CI with HTTP 429. The latest StableAR
        // Lab prerelease contains this exact pinned model. It is only a transport fallback: the model
        // is accepted solely after exact size + SHA-256 verification, so the mutable prerelease tag is
        // never trusted as provenance.
        val fallbackApk = File(output.parentFile, "stablear-model-seed.apk")
        val fallbackUrl = "https://github.com/SirPaul-code/spatial_ar_coop/releases/download/stablear-android-latest/StableAR-Lab-latest.apk"
        if (stableArDownload(fallbackUrl, fallbackApk, attempts = 3)) {
            try {
                ZipFile(fallbackApk).use { zip ->
                    val entry = zip.getEntry("assets/models/xfeat.tflite")
                        ?: error("StableAR Lab fallback APK did not contain assets/models/xfeat.tflite")
                    tmp.delete()
                    zip.getInputStream(entry).buffered().use { input ->
                        tmp.outputStream().buffered().use { outputStream -> input.copyTo(outputStream) }
                    }
                }
                if (install(tmp)) {
                    fallbackApk.delete()
                    tmp.delete()
                    logger.lifecycle("StableAR: recovered verified pinned XFeat model from StableAR Lab release asset")
                    return@doLast
                }
            } finally {
                fallbackApk.delete()
                tmp.delete()
            }
        }

        error(
            "Could not acquire pinned XFeat model. Supply STABLEAR_XFEAT_MODEL=/absolute/path/xfeat.tflite " +
                "or -PstablearXFeatModel=/absolute/path/xfeat.tflite. Expected $xfeatBytes bytes, SHA-256 $xfeatSha256"
        )
    }
}

android {
    namespace="com.sirpaul.stablear.nativevision"; compileSdk=36
    ndkVersion="27.2.12479018"
    defaultConfig {
        minSdk=26
        ndk { abiFilters += listOf("arm64-v8a","x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++20"; arguments += "-DANDROID_STL=c++_shared" } }
    }
    buildFeatures { prefab = true }
    sourceSets.getByName("main").assets.srcDir(xfeatAssetDir)
    externalNativeBuild { cmake { path=file("src/main/cpp/CMakeLists.txt"); version="3.22.1" } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    // OpenCV's official AAR already supplies the single libc++_shared runtime required by
    // opencv_java4 and this JNI library. Do not publish a second NDK copy from StableAR:
    // consuming apps otherwise fail mergeDebugNativeLibs (or, with older AGP, can silently
    // pick an arbitrary libc++ build). The final commercial packaging should continue toward
    // the Android NDK middleware recommendation of one JNI implementation library with a
    // tightly controlled native ABI.
    packaging {
        jniLibs {
            excludes += "**/libc++_shared.so"
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

tasks.named("preBuild").configure { dependsOn(prepareXFeatModel) }

dependencies {
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.google.ai.edge.litert:litert:2.2.0")
}
