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
    implementation(libs.yavi)

    implementation(libs.quarkus.amazon.s3)
    implementation(libs.aws.sdk.url.connection.client)

    compileOnly(libs.bundles.lombok)
    annotationProcessor(libs.bundles.lombok)

    testImplementation(libs.quarkus.junit5)
}

// Dev mode shares one Compose stack: the root `devInfra` task starts it once per build, and
// every dev-mode app then discovers it instead of running its own `compose up`.
tasks.named("quarkusDev") {
    dependsOn(":devInfra")
}
