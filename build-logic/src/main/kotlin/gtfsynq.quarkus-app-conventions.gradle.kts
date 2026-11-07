import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("gtfsynq.java-conventions")
}

// Precompiled script plugins have no `libs.` accessors; go through the catalog extension.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(catalog.findLibrary("quarkus-bom").get()))
    implementation(project(":shared"))

    implementation(catalog.findLibrary("quarkus-config-yaml").get())
    implementation(catalog.findLibrary("quarkus-smallrye-health").get())
    implementation(catalog.findLibrary("quarkus-opentelemetry").get())
    implementation(catalog.findLibrary("quarkus-micrometer-opentelemetry").get())
    implementation(catalog.findLibrary("quarkus-container-image-jib").get())

    testImplementation(catalog.findLibrary("quarkus-junit5").get())
}

// io.floci:testcontainers-floci 2.17.0 is a relocation POM. Gradle follows it to
// testcontainers-floci-aws, but Quarkus' conditional deployment classpath can still load the old
// coordinate without the relocated jar. Map the old coordinate to the real artifact until the
// Amazon Services extension ships a version that declares the new coordinates directly.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.floci" && requested.name == "testcontainers-floci") {
            useTarget("io.floci:testcontainers-floci-aws:${requested.version}")
        }
    }
}

// Dev mode shares one Compose stack: the root `devInfra` task starts it once per build, and
// every dev-mode app then discovers it instead of running its own `compose up`.
tasks.named("quarkusDev") {
    dependsOn(":devInfra")
}
