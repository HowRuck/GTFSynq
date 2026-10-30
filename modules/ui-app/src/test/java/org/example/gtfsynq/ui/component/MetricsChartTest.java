package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.example.gtfsynq.ui.client.MetricsClient.Point;
import org.example.gtfsynq.ui.client.MetricsClient.Series;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MetricsChartTest {

    private static final Instant FIRST = Instant.ofEpochSecond(1700000000);
    private static final Instant SECOND = Instant.ofEpochSecond(1700000060);

    @Test
    void alignsSeriesOnTheSharedTimeAxis() {
        var success = new Series(Map.of("outcome", "success"), List.of(new Point(FIRST, 1), new Point(SECOND, 3)));
        var failure = new Series(Map.of("outcome", "failure"), List.of(new Point(FIRST, 2)));

        var root = new ObjectMapper().readTree(MetricsChart.payload(List.of(success, failure)));
        var data = root.path("data");

        assertEquals("line", root.path("type").asText());
        assertEquals(2, data.path("labels").size());

        var successData = data.path("datasets").path(0).path("data");
        assertEquals("success", data.path("datasets").path(0).path("label").asText());
        assertEquals(3.0, successData.path(1).asDouble());

        var failureData = data.path("datasets").path(1).path("data");
        assertEquals(2.0, failureData.path(0).asDouble());
        // A series that has no value at a timestamp leaves a gap instead of shifting left.
        assertTrue(failureData.path(1).isNull());
    }

    @Test
    void keepsDuplicateAndEmptySeriesNamesReadable() {
        var series = new Series(Map.of(), List.of(new Point(FIRST, 5)));

        var root = new ObjectMapper().readTree(MetricsChart.payload(List.of(series)));

        assertEquals(
                "value",
                root.path("data").path("datasets").path(0).path("label").asText());
    }
}
