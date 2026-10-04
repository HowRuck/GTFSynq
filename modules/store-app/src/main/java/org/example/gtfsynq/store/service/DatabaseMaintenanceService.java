package org.example.gtfsynq.store.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRepository;
import org.example.gtfsynq.store.config.HotDataRetentionConfig;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class DatabaseMaintenanceService {

    private final TripUpdateRepository tripUpdateRepository;
    private final HotDataRetentionConfig hotDataRetentionConfig;

    @Scheduled(every = "{gtfsynq.retention.rate}")
    public void cleanHotData() {
        log.info("Cleaning hot data...");

        var deletedCount = tripUpdateRepository.deleteAllByLastSeenAtBefore(
                LocalDateTime.now().minus(hotDataRetentionConfig.hours()));

        log.info("Deleted {} hot data records.", deletedCount);
    }
}
