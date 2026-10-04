package org.example.gtfsynq.store.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.persistence.OffHeapFileScribe;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapHashStore;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapLongTable;

/**
 * Wires the off-heap dedup state store for the store app. Declared here rather
 * than in the shared module so that only apps that actually use the store pay
 * for it, and so the shared module stays free of framework auto-configuration.
 */
@Singleton
public class OffHeapStateConfig {

    private final OffHeapFileScribe offHeapFileScribe;
    private final OffHeapLongTable offHeapLongTable;
    private final OffHeapHashStore offHeapHashStore;

    @Inject
    public OffHeapStateConfig(
            @ConfigProperty(name = "state.save.path", defaultValue = "state_dump.bin") String path,
            Instance<MeterRegistry> registry) {
        this.offHeapFileScribe = new OffHeapFileScribe(path);
        this.offHeapLongTable = new OffHeapLongTable(offHeapFileScribe);
        this.offHeapHashStore = new OffHeapHashStore(offHeapLongTable);

        if (registry.isResolvable()) {
            offHeapHashStore.bindMetrics(registry.get());
        }
        offHeapHashStore.init();
    }

    @Produces
    @Singleton
    public OffHeapFileScribe offHeapFileScribe() {
        return offHeapFileScribe;
    }

    @Produces
    @Singleton
    public OffHeapLongTable offHeapLongTable() {
        return offHeapLongTable;
    }

    @Produces
    @Singleton
    public OffHeapHashStore offHeapHashStore() {
        return offHeapHashStore;
    }

    public void disposeOffHeapHashStore(@Disposes OffHeapHashStore store) {
        store.close();
    }

    public void disposeOffHeapLongTable(@Disposes OffHeapLongTable table) {
        table.close();
    }

    @Scheduled(every = "60s")
    void tickMinute() {
        offHeapHashStore.tickMinute();
    }

    @Scheduled(every = "60s")
    void autoTune() {
        offHeapHashStore.autoTune();
    }

    @Scheduled(every = "60s")
    void printLoadPercentage() {
        offHeapHashStore.printLoadPercentage();
    }

    @Scheduled(every = "60s")
    void purgeRetiredArenas() {
        offHeapLongTable.purgeRetiredArenas();
    }

    @Scheduled(every = "60s")
    void backup() {
        offHeapLongTable.backup();
    }
}
