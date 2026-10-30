import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    java
    id("com.diffplug.spotless")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "org.example"
version = "0.0.1-SNAPSHOT"

java {
    targetCompatibility = JavaVersion.VERSION_25
    sourceCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

spotless {
    java {
        targetExclude("build/generated/**")
        palantirJavaFormat(catalog.findVersion("palantir-java-format").get().requiredVersion)
        removeUnusedImports()
        importOrder()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Quarkus derives a JPMS module name for every dependency that does not declare
// one, warning for each occurrence ("does not have a defined module name").
// Declare it in the manifest instead: the build stays quiet, and the name no
// longer changes if the group or artifact id is ever renamed.
tasks.withType<Jar>().configureEach {
    manifest {
        // `group` alone would resolve to the Jar task's task group, not the project's.
        attributes("Automatic-Module-Name" to "${project.group}.${project.name.replace('-', '.')}")
    }
}

// The generated protobuf code validates at runtime that protobuf-java is at
// least as new as the protoc that produced it, so the runtime must not be
// downgraded by a platform/BOM constraint.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.google.protobuf") {
            useVersion(catalog.findVersion("protobuf").get().requiredVersion)
        }
    }
}
