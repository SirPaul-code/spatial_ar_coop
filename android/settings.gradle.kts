pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "SpatialNoMap"
include(":app", ":showme", ":stablear-native-vision-android")
project(":stablear-native-vision-android").projectDir = file("../sdk/native-vision-android")
