plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.spotless.plugin.gradle)
    // Provides the `dev.openjavaformat.java-format` plugin, which appends the
    // open-java-format step to Spotless and carries the formatter itself.
    implementation(libs.open.java.format.gradle.plugin)
}
