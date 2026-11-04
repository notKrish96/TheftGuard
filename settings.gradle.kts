// settings.gradle.kts — the first file Gradle reads.
// It says WHERE to download plugins and libraries from, and WHICH modules make up the project.

pluginManagement {
    repositories {
        google()            // Android Gradle Plugin lives here
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modules may not declare their own repositories. Keeping the list in one place
    // means a dependency can't sneak in from some random repo (a supply-chain habit worth having).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TheftGuard"
include(":app")   // single-module project, as agreed
