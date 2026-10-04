package org.example.gtfsynq.ingest.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.persistence.OffHeapFileScribe;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapHashStore;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapLongTable;

/**
 * Wires the off-heap dedup state store for the ingest app and drives its periodic
 * maintenance. Declared here rather than in the shared module so that only apps that
 * actually use the store pay for it, and so the shared module stays framework-free.
 * <p>
 * The store is constructed eagerly, bound to the Micrometer registry when one is present,
 * and then initialised. The five scheduled tasks that refresh the cached minute, auto-tune
 * the table, print the load percentage, purge retired arenas and write the state dump all
 * live here because the shared classes carry no scheduling annotations of their own.
 */
@Singleton
public class OffHeapStateConfig {

    private final OffHeapFileScribe scribe;
    private final OffHeapLongTable table;
    private final OffHeapHashStore store;

    @Inject
    public OffHeapStateConfig(
            @ConfigProperty(name = "state.save.path", defaultValue = "state_dump.bin") String path,
            Instance<MeterRegistry> registry) {
        this.scribe = new OffHeapFileScribe(path);
        this.table = new OffHeapLongTable(scribe);
        this.store = new OffHeapHashStore(table);

        if (registry.isResolvable()) {
            store.bindMetrics(registry.get());
        }

        store.init();
    }

    @Produces
    @Singleton
    public OffHeapFileScribe offHeapFileScribe() {
        return scribe;
    }

    @Produces
    @Singleton
    public OffHeapLongTable offHeapLongTable() {
        return table;
    }

    @Produces
    @Singleton
    public OffHeapHashStore offHeapHashStore() {
        return store;
    }

    /**
     * Refreshes the cached current minute.
     */
    @Scheduled(every = "60s")
    public void tickMinute() {
        store.tickMinute();
    }

    /**
     * Runs the periodic maintenance resize scan.
     */
    @Scheduled(every = "60s")
    public void autoTune() {
        store.autoTune();
    }

    /**
     * Logs the current store load percentage.
     */
    @Scheduled(every = "60s")
    public void printLoadPercentage() {
        store.printLoadPercentage();
    }

    /**
     * Frees arenas that were retired at least a minute ago.
     */
    @Scheduled(every = "60s")
    public void purgeRetiredArenas() {
        table.purgeRetiredArenas();
    }

    /**
     * Writes the periodic state dump through the scribe.
     */
    @Scheduled(every = "60s")
    public void backup() {
        table.backup();
    }
}
