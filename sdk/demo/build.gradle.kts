plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace="com.sirpaul.stablear.demo"; compileSdk=36
    defaultConfig {
        applicationId="com.sirpaul.stablear.demo"; minSdk=26; targetSdk=36
        versionCode=1; versionName="0.1.0-research"; ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    // Research APK only, never the production signing identity or ShowMe package.
    buildTypes { getByName("release") { isMinifyEnabled=false } }
}
dependencies {
    implementation(project(":arcore")); implementation(project(":vision"))
    implementation("com.google.ar:core:1.56.0")
    implementation("org.opencv:opencv:4.12.0")
}
