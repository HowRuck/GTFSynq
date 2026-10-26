plugins {
    alias(libs.plugins.quarkus)
    id("gtfsynq.java-conventions")
}

dependencies {
    // Vaadin's BOM must win over Quarkus' where the two overlap.
    implementation(platform(libs.vaadin.bom))
    implementation(platform(libs.quarkus.bom))
    implementation(project(":shared"))

    implementation(libs.vaadin.quarkus.extension)

    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.grpc)
    // Session-lock diagnostics run on a timer so a wedged lock is reported while it is still held.
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.micrometer.opentelemetry)
    implementation(libs.quarkus.container.image.jib)

    compileOnly(libs.bundles.lombok)
    annotationProcessor(libs.bundles.lombok)

    testImplementation(libs.quarkus.junit5)
}
