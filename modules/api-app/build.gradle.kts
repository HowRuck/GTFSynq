plugins {
    alias(libs.plugins.quarkus)
    id("gtfsynq.quarkus-app-conventions")
}

dependencies {
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.grpc)
    implementation(libs.quarkus.jdbc.postgresql)
}
