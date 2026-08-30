package org.example.gtfsynq.store.service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRepository;
import org.example.gtfsynq.store.config.HotDataRetentionConfig;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseMaintenanceService {

    private final TripUpdateRepository tripUpdateRepository;
    private final HotDataRetentionConfig hotDataRetentionConfig;

    @Scheduled(fixedDelayString = "${gtfsynq.retention.rate-minutes:15}", timeUnit = TimeUnit.MINUTES)
    public void cleanHotData() {
        log.info("Cleaning hot data...");

        var deletedCount = tripUpdateRepository.deleteAllByLastSeenAtBefore(
                LocalDateTime.now().minus(hotDataRetentionConfig.hours()));

        log.info("Deleted {} hot data records.", deletedCount);
    }
}
