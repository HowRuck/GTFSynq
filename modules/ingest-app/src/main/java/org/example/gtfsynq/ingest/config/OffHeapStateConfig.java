package org.example.gtfsynq.ingest.config;

import org.example.gtfsynq.shared.persistence.OffHeapFileScribe;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapHashStore;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapLongTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the off-heap dedup state store for the ingest app. Declared here rather
 * than in the shared module so that only apps that actually use the store pay
 * for it, and so the shared module stays free of Spring Boot auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
public class OffHeapStateConfig {

    @Bean
    public OffHeapFileScribe offHeapFileScribe(@Value("${state.save.path:state_dump.bin}") String path) {
        return new OffHeapFileScribe(path);
    }

    @Bean
    public OffHeapLongTable offHeapLongTable(OffHeapFileScribe scribe) {
        return new OffHeapLongTable(scribe);
    }

    @Bean
    public OffHeapHashStore offHeapHashStore(OffHeapLongTable binTable) {
        return new OffHeapHashStore(binTable);
    }
}
