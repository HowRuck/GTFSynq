pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.6.0"
    id("com.autonomousapps.build-health") version "3.19.2"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Vaadin prereleases (the UI is built on a 25.4 alpha).
        maven {
            name = "vaadinPrereleases"
            url = uri("https://maven.vaadin.com/vaadin-prereleases")
            content {
                includeGroupByRegex("com\\.vaadin.*")
            }
        }
    }
}

// Publish a build scan to the free public service (https://scans.gradle.com) for
// every build, so developer machines and CI runs alike produce a shareable
// report. Agreeing to the terms here keeps publishing non-interactive.
// Opt a single local build out with `--no-scan`.
develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
        publishing.onlyIf { true }
    }
}

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")

rootProject.name = "GTFSynq"

include("shared", "ingest-app", "store-app", "api-app", "ui-app")

project(":shared").projectDir = file("modules/shared")
project(":ingest-app").projectDir = file("modules/ingest-app")
project(":store-app").projectDir = file("modules/store-app")
project(":api-app").projectDir = file("modules/api-app")
project(":ui-app").projectDir = file("modules/ui-app")
