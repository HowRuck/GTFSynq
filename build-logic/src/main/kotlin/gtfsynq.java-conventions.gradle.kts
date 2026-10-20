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
