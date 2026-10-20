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
    implementation(libs.quarkus.messaging.kafka)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.micrometer.opentelemetry)
    implementation(libs.quarkus.container.image.jib)

    // Reads the stored static feed archives back for validation. As in ingest-app, the extension
    // marks all HTTP clients optional, so the sync client needs one supplied explicitly.
    implementation(libs.quarkus.amazon.s3)
    implementation(libs.aws.sdk.url.connection.client)

    runtimeOnly(libs.flyway.database.postgresql)

    compileOnly(libs.bundles.lombok)
    annotationProcessor(libs.bundles.lombok)

    testImplementation(libs.quarkus.junit5)
}
