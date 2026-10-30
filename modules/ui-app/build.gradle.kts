plugins {
    alias(libs.plugins.quarkus)
    alias(libs.plugins.vaadin)
    id("gtfsynq.java-conventions")
}

// The Quarkus extension still owns the production frontend build; the Vaadin
// plugin exposes `vaadinPrepareFrontend` and `vaadinBuildFrontend` for running
// the frontend toolchain directly. Keep it on pnpm, matching the committed
// lockfile and .npmrc.
vaadin {
    pnpmEnable = true
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
    implementation(libs.quarkus.container.image.buildpack)

    compileOnly(libs.bundles.lombok)
    annotationProcessor(libs.bundles.lombok)

    testImplementation(libs.quarkus.junit5)
}
