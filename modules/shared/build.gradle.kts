import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `java-library`
    id("gtfsynq.java-conventions")
    alias(libs.plugins.protobuf)
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    api(libs.protobuf.java)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.javatuples)
    api(libs.yavi)
    api(libs.fastcsv)
    implementation(libs.zero.allocation.hashing)
    implementation(libs.micrometer.jvm.extras)

    // Provided by the Quarkus applications that consume this library.
    compileOnly(libs.jakarta.inject.api)
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.microprofile.config.api)
    compileOnly(libs.slf4j.api)

    compileOnly(libs.jspecify)
    compileOnly(libs.bundles.lombok)
    annotationProcessor(libs.bundles.lombok)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${catalog.findVersion("protobuf").get().requiredVersion}"
    }
    plugins {
        // Generates the GtfsQueryService client/server stubs used by api-app
        // (server) and ui-app (client).
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${catalog.findVersion("grpc").get().requiredVersion}"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
            }
        }
    }
}
