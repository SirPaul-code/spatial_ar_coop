import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace="com.sirpaul.stablear.demo"; compileSdk=36
    defaultConfig {
        applicationId="com.sirpaul.stablear.demo"; minSdk=26; targetSdk=36
        versionCode=1; versionName="0.2.0-xfeat"; ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    // Research APK only, never the production signing identity or ShowMe package.
    buildTypes { getByName("release") { isMinifyEnabled=false } }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":arcore")); implementation(project(":vision")); implementation(project(":native-vision-android"))
    implementation("com.google.ar:core:1.56.0")
    implementation("org.opencv:opencv:4.12.0")
}
