// Avrea's self-hosted runners point Gradle at an internal Maven proxy
// (https://cache.avrea.com:8443/maven) that mirrors a curated subset of Maven
// Central and has no upstream fallback. An artifact released after the proxy's
// last sync then fails resolution even though Maven Central has it, which took
// CI down for am.ik.yavi:yavi:0.17.2.
//
// This init script is passed with --init-script from the workflows, so it is
// evaluated after the runner's own init scripts. The proxy therefore stays the
// first repository (its cache keeps serving normal builds) and Maven Central is
// only consulted when the proxy reports a miss.
settingsEvaluated {
    dependencyResolutionManagement.repositories {
        mavenCentral()
    }
}
