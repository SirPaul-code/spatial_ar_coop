pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "SpatialNoMap"
include(":app", ":showme", ":core", ":arcore", ":vision")
project(":core").projectDir = file("../sdk/core")
project(":arcore").projectDir = file("../sdk/arcore")
project(":vision").projectDir = file("../sdk/vision")
