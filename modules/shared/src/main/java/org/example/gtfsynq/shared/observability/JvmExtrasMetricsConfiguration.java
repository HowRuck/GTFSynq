package org.example.gtfsynq.shared.observability;

import io.github.mweirauch.micrometer.jvm.extras.ProcessMemoryMetrics;
import io.github.mweirauch.micrometer.jvm.extras.ProcessThreadMetrics;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Exposes the JMX-backed JVM extras binders as CDI beans.
 *
 * <p>
 * Quarkus' Micrometer integration automatically registers every CDI bean that
 * implements {@code MeterBinder}, so declaring these as beans is enough to add
 * the {@code process.memory.*} / {@code process.threads.*} meters that the
 * supplied Grafana dashboards query.
 */
@Singleton
public class JvmExtrasMetricsConfiguration {

    @Produces
    @Singleton
    ProcessMemoryMetrics processMemoryMetrics() {
        return new ProcessMemoryMetrics();
    }

    @Produces
    @Singleton
    ProcessThreadMetrics processThreadMetrics() {
        return new ProcessThreadMetrics();
    }
}
