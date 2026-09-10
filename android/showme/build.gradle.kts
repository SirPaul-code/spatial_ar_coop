import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Reuse the actual existing geometry/surface pipeline without modifying :app.
// Generated copies are build output, not a fork of the algorithms.
val spatialSourceNames = listOf(
    "Models.kt", "MetricSupportSampler.kt", "CameraBackgroundRenderer.kt",
    "AlignmentEngine.kt", "EssentialSharedPoseSolver.kt", "SharedVisualAnchorSolver.kt",
    "FusionMath.kt", "RigidTransformMath.kt", "SurfaceTargetResolver.kt", "SurfaceEdgeSnapRefiner.kt",
)
val sharedSources = layout.buildDirectory.dir("generated/spatial-sources")
val prepareSpatialSources = tasks.register<Sync>("prepareSpatialSources") {
    from(rootProject.file("app/src/main/java")) {
        spatialSourceNames.forEach { include("com/sirpaul/spatialnomap/$it") }
    }
    into(sharedSources)
}
val browserAssets = layout.buildDirectory.dir("generated/browser-assets")
val prepareBrowserAssets = tasks.register<Sync>("prepareBrowserAssets") {
    from(rootProject.file("../showme/web")) { exclude("*.test.mjs", "package.json", "tests/**") }
    into(browserAssets.map { it.dir("showme") })
}
val devStore = layout.buildDirectory.file("generated/signing/showme-dev.p12").get().asFile
if (!devStore.exists()) {
    devStore.parentFile.mkdirs()
    devStore.writeBytes(Base64.getMimeDecoder().decode(rootProject.file("keystore/spatial-sync-dev.p12.b64").readText()))
}
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val runAttempt = System.getenv("GITHUB_RUN_ATTEMPT")?.toIntOrNull() ?: 0

android {
    namespace = "com.sirpaul.showme"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sirpaul.showme"
        minSdk = 33
        targetSdk = 36
        versionCode = 100000 + runNumber * 10 + runAttempt
        versionName = "0.1.0-local.$runNumber.$runAttempt"
        // Commodity phones used for the physical trial are ARM64.
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("localTrial") {
            storeFile = devStore
            storePassword = "spatialdevpass"
            keyAlias = "spatial-dev"
            keyPassword = "spatialdevpass"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("localTrial") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("localTrial")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    sourceSets.getByName("main").java.srcDir(sharedSources)
    sourceSets.getByName("main").assets.srcDir(browserAssets)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA") }
}
tasks.named("preBuild").configure { dependsOn(prepareSpatialSources, prepareBrowserAssets) }
tasks.configureEach {
    if (name.startsWith("compile") && name.endsWith("Kotlin")) dependsOn(prepareSpatialSources)
}
dependencies {
    implementation("com.google.ar:core:1.56.0")
    implementation("org.opencv:opencv:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
