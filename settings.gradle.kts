pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // Settings plugins are resolved before Gradle creates the version-catalog accessors.
    // This pinned value is checked against the catalog by verifyDependencyPins.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Glance"
include(":app")
include(":core-common")
include(":core-crypto")
include(":core-network")
include(":core-data")
include(":core-security")
