plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
// Reuse the actual, unchanged spatial pipeline. No fork of the solvers and no
// Wi-Fi Aware / AlignmentCoordinator / sensor or vehicle startup in ShowMe.
val sharedSources = tasks.register<Sync>("syncSpatialSources") {
    from(rootProject.file("../../android/app/src/main/java/com/sirpaul/spatialnomap")) {
        include("Models.kt", "MetricSupportSampler.kt", "CameraBackgroundRenderer.kt",
            "ArCoreCompat.kt", "SurfaceTargetResolver.kt", "SurfaceEdgeSnapRefiner.kt",
            "AlignmentEngine.kt", "EssentialSharedPoseSolver.kt", "SharedVisualAnchorSolver.kt",
            "FusionMath.kt", "RigidTransformMath.kt")
    }
    into(layout.buildDirectory.dir("generated/spatial/com/sirpaul/spatialnomap"))
}
android {
    namespace = "com.sirpaul.showme.spatial"
    compileSdk = 36
    defaultConfig { minSdk = 33 }
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/spatial"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
tasks.named("preBuild").configure { dependsOn(sharedSources) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(sharedSources) }
dependencies {
    api("com.google.ar:core:1.56.0")
    api("org.opencv:opencv:4.12.0")
}
