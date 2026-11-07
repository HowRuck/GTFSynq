// See the root settings.gradle.kts: the central repository API is still @Incubating in Gradle.
@file:Suppress("UnstableApiUsage", "GradleUnstableApiUsage")

dependencyResolutionManagement {
    repositories {
        // The portal only serves the Spotless plugin (marker and artifacts);
        // every library resolves from Central.
        gradlePluginPortal {
            content {
                includeGroupByRegex("com\\.diffplug\\..*")
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
