plugins {
    // Vaadin 25.4's Quarkus extension is built against Quarkus 3.33. This module
    // therefore pins its own plugin version instead of using the backend's
    // 4.0 beta. The backend apps keep their existing Quarkus version.
    id("io.quarkus") version "3.33.0"
    id("gtfsynq.java-conventions")
}

dependencies {
    // Vaadin's BOM must win over Quarkus' where the two overlap.
    implementation(enforcedPlatform(libs.vaadin.bom))
    implementation(enforcedPlatform(libs.quarkus.bom.vaadin))
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
