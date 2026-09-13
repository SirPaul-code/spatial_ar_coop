pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "SpatialNoMap"

// Keep normal IDE/full-root behavior unchanged, but do not configure :showme when
// CI explicitly requests only :app tasks. This keeps the StableAR host build isolated
// from unrelated ShowMe Gradle/Kotlin plugin state.
val requestedTasks = gradle.startParameter.taskNames
val appOnlyInvocation = requestedTasks.isNotEmpty() && requestedTasks.all { task ->
    task == ":app" || task.startsWith(":app:")
}
include(":app")
if (!appOnlyInvocation) {
    include(":showme")
}

// Consume StableAR as an independent composite build so the SDK keeps its own
// Kotlin/AGP toolchain and product modules do not duplicate SDK source or plugin state.
includeBuild("../sdk")
