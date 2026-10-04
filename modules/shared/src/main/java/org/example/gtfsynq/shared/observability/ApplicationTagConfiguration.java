package org.example.gtfsynq.shared.observability;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Tags every exported meter with the application name.
 *
 * <p>
 * Quarkus Micrometer only applies common tags that are contributed by a
 * {@code MeterFilter} bean, so without this every metric would be indistinguishable
 * between the three apps in the shared Prometheus registry. The dashboards select on
 * {@code application}, and the VictoriaMetrics scrape config labels each target with
 * the same value.
 */
@Singleton
public class ApplicationTagConfiguration {

    /**
     * Contributes the {@code application} common tag.
     *
     * @param applicationName value of {@code quarkus.application.name}
     * @return a Micrometer filter adding the tag to all meters
     */
    @Produces
    @Singleton
    MeterFilter applicationTag(@ConfigProperty(name = "quarkus.application.name") String applicationName) {
        return MeterFilter.commonTags(List.of(Tag.of("application", applicationName)));
    }
}
