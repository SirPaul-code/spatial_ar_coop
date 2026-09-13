pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "SpatialNoMap"
include(":app", ":showme")

// Consume StableAR as an independent composite build so the SDK keeps its own
// Kotlin/AGP toolchain and product modules do not duplicate SDK source or plugin state.
includeBuild("../sdk")
