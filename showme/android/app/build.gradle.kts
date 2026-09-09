import java.util.Base64
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val devStore = layout.buildDirectory.file("generated/signing/showme-dev.p12").get().asFile
if (!devStore.exists()) {
    devStore.parentFile.mkdirs()
    devStore.writeBytes(Base64.getMimeDecoder().decode(rootProject.file("../../android/keystore/spatial-sync-dev.p12.b64").readText().trim()))
}
val webAssets = tasks.register<Sync>("prepareWebAssets") {
    from(rootProject.file("../web")) { include("index.html", "app.css", "app.mjs", "protocol.mjs") }
    into(layout.buildDirectory.dir("generated/web-assets"))
}
android {
    namespace = "com.sirpaul.showme"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sirpaul.showme"
        minSdk = 33
        targetSdk = 36
        versionCode = 10000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) * 10 + (System.getenv("GITHUB_RUN_ATTEMPT")?.toIntOrNull() ?: 0)
        versionName = "0.1.0-local." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
        buildConfigField("String", "BUILD_SHA", "\"${System.getenv("GITHUB_SHA")?.take(8) ?: "local"}\"")
    }
    signingConfigs {
        create("sideload") { storeFile = devStore; storePassword = "spatialdevpass"; keyAlias = "spatial-dev"; keyPassword = "spatialdevpass" }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("sideload") }
        getByName("release") { signingConfig = signingConfigs.getByName("sideload"); isDebuggable = false; isMinifyEnabled = false }
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/web-assets"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES") }
}
tasks.named("preBuild").configure { dependsOn(webAssets) }
dependencies {
    implementation(project(":spatial"))
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
