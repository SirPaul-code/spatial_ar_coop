plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace="com.sirpaul.stablear.nativeandroid"; compileSdk=36
    ndkVersion="27.2.12479018"
    defaultConfig { minSdk=26; ndk { abiFilters += listOf("arm64-v8a","x86_64") }; externalNativeBuild { cmake { cppFlags += "-std=c++20" } } }
    externalNativeBuild { cmake { path=file("src/main/cpp/CMakeLists.txt"); version="3.22.1" } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    buildFeatures { prefab = true }
}
dependencies { compileOnly("com.google.ar:core:1.56.0") }
