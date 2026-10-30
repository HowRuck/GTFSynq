package org.example.gtfsynq.ui.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MetricsClientTest {

    @Test
    void parsesEverySeriesAndDerivesItsName() {
        var series = MetricsClient.parse("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"outcome":"success"},"values":[[1700000000,"1.5"],[1700000060,"2"]]},
                  {"metric":{"__name__":"up"},"values":[[1700000000,"1"]]}
                ]}}
                """);

        assertEquals(2, series.size());
        assertEquals("success", series.get(0).name());
        assertEquals(2, series.get(0).points().size());
        assertEquals(
                Instant.ofEpochSecond(1700000060), series.get(0).points().get(1).timestamp());
        assertEquals(2.0, series.get(0).points().get(1).value());
        assertEquals("up", series.get(1).name());
    }

    @Test
    void skipsValuesThatAreNotNumbers() {
        var series = MetricsClient.parse("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"result":"valid"},"values":[[1700000000,"NaN"],[1700000060,"3"]]}
                ]}}
                """);

        assertEquals(1, series.get(0).points().size());
        assertEquals(3.0, series.get(0).points().get(0).value());
    }

    @Test
    void reportsAQueryError() {
        var failure = assertThrows(
                MetricsException.class, () -> MetricsClient.parse("{\"status\":\"error\",\"error\":\"bad query\"}"));

        assertTrue(failure.getMessage().contains("bad query"));
    }

    @Test
    void namesASeriesWithoutGroupingLabelsAfterItsMetric() {
        var series = MetricsClient.parse("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"__name__":"kafka_consumergroup_lag"},"values":[[1700000000,"4"]]}
                ]}}
                """);

        assertEquals("kafka_consumergroup_lag", series.get(0).name());
    }
}
