// See the root settings.gradle.kts: the central repository API is still @Incubating in Gradle.
@file:Suppress("UnstableApiUsage", "GradleUnstableApiUsage")

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
