package org.example.gtfsynq.shared.observability;

import io.github.mweirauch.micrometer.jvm.extras.ProcessMemoryMetrics;
import io.github.mweirauch.micrometer.jvm.extras.ProcessThreadMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JvmExtrasMetricsConfiguration {

    @Bean
    ProcessMemoryMetrics processMemoryMetrics() {
        return new ProcessMemoryMetrics();
    }

    @Bean
    ProcessThreadMetrics processThreadMetrics() {
        return new ProcessThreadMetrics();
    }
}
