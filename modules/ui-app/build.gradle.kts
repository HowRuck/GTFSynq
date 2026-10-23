plugins {
    alias(libs.plugins.quarkus)
    id("gtfsynq.java-conventions")
}

dependencies {
    // Vaadin's BOM must win over Quarkus' where the two overlap.
    implementation(enforcedPlatform(libs.vaadin.bom))
    implementation(platform(libs.quarkus.bom))
    implementation(project(":shared"))

    implementation(libs.vaadin.quarkus.extension)

    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.grpc)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.micrometer.opentelemetry)
    implementation(libs.quarkus.container.image.jib)

    compileOnly(libs.bundles.lombok)
    annotationProcessor(libs.bundles.lombok)

    testImplementation(libs.quarkus.junit5)
}
