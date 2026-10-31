plugins {
    id("io.github.ben-manes.versions") version "0.66.0"
    alias(libs.plugins.spotless) apply false
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
// The app modules wire this in as a dependency of their `quarkusDev` task.
val devInfra by tasks.registering(Exec::class) {
    group = "application"
    description = "Starts the local development infrastructure from docker-compose.dev.yaml."

    val pathDirectories = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
    val composeCli = listOf("docker", "podman").firstOrNull { cli ->
        pathDirectories.any { directory -> java.io.File(directory, cli).canExecute() }
    } ?: "docker"

    workingDir = layout.projectDirectory.asFile
    commandLine(composeCli, "compose", "-f", "docker-compose.dev.yaml", "up", "-d")
}

