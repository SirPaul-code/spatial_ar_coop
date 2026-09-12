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

// Keep the physically proven app build file byte-for-byte intact (it also owns the existing
// sideload signing setup). Inject StableAR only as normal Gradle project dependencies.
gradle.beforeProject {
    if (path == ":app") {
        plugins.withId("com.android.application") {
            dependencies.add("implementation", project(":core"))
            dependencies.add("implementation", project(":arcore"))
            dependencies.add("implementation", project(":vision"))
            dependencies.add("implementation", project(":native-vision-android"))
        }
    }
}
