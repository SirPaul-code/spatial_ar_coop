import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

val xfeatRevision = "bd421aad1ce6d25dc172cd9579cc13b9da21356f"
val xfeatSha256 = "6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df"
val xfeatAssetDir = layout.buildDirectory.dir("generated/xfeat-assets")
val xfeatModelFile = xfeatAssetDir.map { it.file("models/xfeat.tflite") }

val prepareXFeatModel by tasks.registering {
    outputs.file(xfeatModelFile)
    doLast {
        val output = xfeatModelFile.get().asFile
        output.parentFile.mkdirs()
        fun sha256(file: File): String {
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
        if (!output.exists() || sha256(output) != xfeatSha256) {
            val tmp = File(output.parentFile, "${output.name}.tmp")
            tmp.delete()
            val url = "https://huggingface.co/litert-community/xfeat-litert/resolve/$xfeatRevision/xfeat.tflite?download=true"
            URI(url).toURL().openStream().buffered().use { input -> tmp.outputStream().buffered().use { input.copyTo(it) } }
            val actual = sha256(tmp)
            check(actual == xfeatSha256) { "XFeat SHA-256 mismatch: expected $xfeatSha256, got $actual" }
            check(tmp.length() == 1_414_480L) { "Unexpected XFeat size: ${tmp.length()}" }
            check(tmp.renameTo(output)) { "Could not install verified XFeat model" }
        }
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
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

tasks.named("preBuild").configure { dependsOn(prepareXFeatModel) }

dependencies {
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.google.ai.edge.litert:litert:2.2.0")
}
