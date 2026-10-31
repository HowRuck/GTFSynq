package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.example.gtfsynq.ui.client.MetricsClient.Point;
import org.example.gtfsynq.ui.client.MetricsClient.Series;
import org.junit.jupiter.api.Test;

class HeaderUsageTest {

    @Test
    void picksTheNewestPointOfEveryLabelledSeries() {
        var cpu = new Series(
                Map.of("metric", "app_cpu"),
                List.of(new Point(Instant.EPOCH, 10), new Point(Instant.EPOCH.plusSeconds(30), 12)));
        var memory = new Series(Map.of("metric", "app_memory"), List.of(new Point(Instant.EPOCH, 1024)));

        var values = HeaderUsage.latestValues(List.of(cpu, memory));

        assertEquals(12, values.get("app_cpu"));
        assertEquals(1024, values.get("app_memory"));
    }

    @Test
    void ignoresSeriesWithoutAMetricLabel() {
        var series = new Series(Map.of(), List.of(new Point(Instant.EPOCH, 1)));

        assertTrue(HeaderUsage.latestValues(List.of(series)).isEmpty());
    }

    @Test
    void ignoresSeriesWithoutPoints() {
        var series = new Series(Map.of("metric", "app_cpu"), List.of());

        assertTrue(HeaderUsage.latestValues(List.of(series)).isEmpty());
    }
}
