// See the root settings.gradle.kts: the central repository API is still @Incubating in Gradle.
@file:Suppress("UnstableApiUsage", "GradleUnstableApiUsage")

dependencyResolutionManagement {
    repositories {
        // The portal serves the Spotless plugin and the open-java-format Gradle
        // plugins (with their formatter bundled); every library resolves from
        // Central.
        gradlePluginPortal {
            content {
                includeGroupByRegex("com\\.diffplug\\..*")
                includeGroup("dev.openjavaformat")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
