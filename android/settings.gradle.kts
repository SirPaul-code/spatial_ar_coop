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

rootProject.name = "SpatialArCoop"
include(":app", ":core", ":arcore", ":vision", ":native-vision-android")

// Product integration consumes the SDK as ordinary Gradle modules. The SDK remains independently
// buildable/publishable from ../sdk; product code must not be copied into the SDK runtime.
project(":core").projectDir = file("../sdk/core")
project(":arcore").projectDir = file("../sdk/arcore")
project(":vision").projectDir = file("../sdk/vision")
project(":native-vision-android").projectDir = file("../sdk/native-vision-android")
