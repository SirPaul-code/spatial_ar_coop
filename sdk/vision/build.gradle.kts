import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); `maven-publish` }
android {
    namespace="com.sirpaul.stablear.vision"; compileSdk=36
    defaultConfig { minSdk=26 }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies { api(project(":core")); implementation("org.opencv:opencv:4.12.0") }
afterEvaluate { publishing { publications { create<MavenPublication>("vision") { from(components["release"]) } } } }
