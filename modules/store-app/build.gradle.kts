plugins {
    alias(libs.plugins.quarkus)
    id("gtfsynq.quarkus-app-conventions")
}

dependencies {
    implementation(platform(libs.quarkus.amazon.services.bom))

    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.messaging.kafka)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)

    // Reads the stored static feed archives back for validation. As in ingest-app, the extension
    // marks all HTTP clients optional, so the sync client needs one supplied explicitly.
    implementation(libs.quarkus.amazon.s3)
    implementation(libs.aws.sdk.url.connection.client)

    runtimeOnly(libs.flyway.database.postgresql)
}
