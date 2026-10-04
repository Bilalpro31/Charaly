pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CHARALY"

// :charaly-runtime  - pure Kotlin/JVM. All deterministic story logic, the
//                      inference port, and persistence. Unit tested on the JVM.
// :app              - Android application. UI, llama.cpp JNI, storage glue.
include(":charaly-runtime")
include(":app")
