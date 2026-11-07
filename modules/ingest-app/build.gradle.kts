plugins {
    alias(libs.plugins.quarkus)
    id("gtfsynq.java-conventions")
}

dependencies {
    implementation(platform(libs.quarkus.bom))
    implementation(platform(libs.quarkus.amazon.services.bom))
    implementation(project(":shared"))

    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.kafka.client)

    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.micrometer.opentelemetry)
    implementation(libs.quarkus.container.image.jib)
    implementation(libs.zero.allocation.hashing)

    implementation(libs.quarkus.amazon.s3)
    implementation(libs.aws.sdk.url.connection.client)

    compileOnly(libs.bundles.lombok)
    annotationProcessor(libs.bundles.lombok)

    testImplementation(libs.quarkus.junit5)
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
