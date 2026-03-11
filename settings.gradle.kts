import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.mavenCentral

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
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        maven { url = uri("https://maven.google.com") }
    }
}

rootProject.name = "AgenticAiFunctionCalling"
include(":app")
 