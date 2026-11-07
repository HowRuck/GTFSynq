plugins {
    alias(libs.plugins.quarkus)
    id("gtfsynq.quarkus-app-conventions")
}

dependencies {
    implementation(platform(libs.quarkus.amazon.services.bom))

    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.kafka.client)

    implementation(libs.zero.allocation.hashing)

    implementation(libs.quarkus.amazon.s3)
    implementation(libs.aws.sdk.url.connection.client)
}
