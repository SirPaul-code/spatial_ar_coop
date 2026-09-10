plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); `maven-publish` }
android {
    namespace="com.sirpaul.stablear.arcore"; compileSdk=36
    defaultConfig { minSdk=26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    api(project(":core"))
    // Host explicitly accepts Google's terms and supplies this dependency; not embedded in our AAR.
    compileOnly("com.google.ar:core:1.56.0")
}
afterEvaluate { publishing { publications { create<MavenPublication>("arcore") { from(components["release"]) } } } }
