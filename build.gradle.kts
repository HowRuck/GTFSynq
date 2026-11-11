import java.io.File

plugins {
    id("io.github.ben-manes.versions") version "0.67.0"
    alias(libs.plugins.spotless) apply false
    // Loads the open-java-format plugin once in the root classloader. Without it, every
    // subproject that also applies Quarkus/Vaadin gets its own copy of the plugin classes,
    // and the root-level provider registers its configuration twice.
    alias(libs.plugins.open.java.format) apply false
}

// Quarkus apps are packaged by the `io.quarkus` plugin (see modules/*/build.gradle.kts)
// and containerised by the Jib extension, configured in each app's
// application.yaml:
//
//   ./gradlew :api-app:build                          # JVM fast-jar
//   ./gradlew :api-app:imageBuild                     # local container image
//   ./gradlew :api-app:imageBuild -Dquarkus.container-image.push=true
//   ./gradlew :api-app:build -Dnative                 # native executable

// Compose Dev Services in an app discovers an already running stack, but a single
// `./gradlew quarkusDev` starts every app at once: each one would call `compose up` at the
// same time and collide on the fixed container names. Running compose once here, before any
// dev-mode app, keeps it to a single invocation; the apps then only discover the services.
//
// The quarkus-app convention plugin wires this in as a dependency of each app's `quarkusDev` task.
tasks.register<Exec>("devInfra") {
    group = "application"
    description = "Starts the local development infrastructure from docker-compose.dev.yaml."

    // The compose CLI is picked from PATH at execution time; probing the filesystem
    // during configuration would run whenever this task is realized.
    inputs.property("composePath", providers.environmentVariable("PATH"))

    workingDir = layout.projectDirectory.asFile
    doFirst {
        val pathDirectories = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        val composeCli = listOf("docker", "podman").firstOrNull { cli ->
            pathDirectories.any { directory -> File(directory, cli).canExecute() }
        } ?: "docker"
        commandLine(composeCli, "compose", "-f", "docker-compose.dev.yaml", "up", "-d")
    }
}
