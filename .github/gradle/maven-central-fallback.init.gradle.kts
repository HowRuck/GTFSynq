// Avrea's self-hosted runners point Gradle at an internal Maven proxy
// (https://cache.avrea.com:8443/maven) that mirrors a curated subset of Maven
// Central and has no upstream fallback. An artifact released after the proxy's
// last sync then fails resolution even though Maven Central has it, which took
// CI down for am.ik.yavi:yavi:0.17.2.
//
// The proxy is declared on the projects themselves, and Gradle's default
// repositoriesMode (PREFER_PROJECT) ignores settings repositories for any
// project that declares its own. Adding only Central at project level would
// therefore hide repositories that exist solely in settings (the Vaadin
// prerelease feed), so the settings repositories are re-declared on the
// projects first and Central is appended as the last-resort source.
//
// This init script is passed with --init-script from the workflows, so it is
// evaluated after the runner's own init scripts. The proxy stays first (its
// cache keeps serving normal builds) and Maven Central is consulted only when
// every project-local and settings repository reports a miss.
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

val settingsRepositoryUrls = mutableListOf<java.net.URI>()

settingsEvaluated { settings ->
    settingsRepositoryUrls += settings.dependencyResolutionManagement.repositories
        .filterIsInstance<MavenArtifactRepository>()
        .map { it.url }
}

allprojects {
    repositories {
        settingsRepositoryUrls
            // Central is added below; avoid declaring the same source twice, and
            // deduplicate when several builds report the same settings entry.
            .filterNot { it.host == "repo.maven.apache.org" || it.host == "repo1.maven.org" }
            .distinct()
            .forEach { url -> maven { setUrl(url) } }
        mavenCentral()
    }
}
