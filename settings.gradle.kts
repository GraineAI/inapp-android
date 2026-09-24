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
rootProject.name = "graine-inapp-android"
include(":graine-inapp")
// A real app using the SDK — the integration guide, compiled, and what the device test runs.
include(":sample")
