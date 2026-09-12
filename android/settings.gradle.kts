pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SpatialNoMap"
include(":app", ":core", ":arcore", ":vision", ":native-vision-android")
project(":core").projectDir = file("../sdk/core")
project(":arcore").projectDir = file("../sdk/arcore")
project(":vision").projectDir = file("../sdk/vision")
project(":native-vision-android").projectDir = file("../sdk/native-vision-android")
