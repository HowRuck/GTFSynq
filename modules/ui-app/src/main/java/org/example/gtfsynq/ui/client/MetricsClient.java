package org.example.gtfsynq.ui.client;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads metrics from VictoriaMetrics for the console's metrics view.
 *
 * <p>The monitoring stack receives every app's metrics over OTLP, so VictoriaMetrics — not the
 * backend — is the only place where cluster-wide values such as ingest rates or Kafka lag exist.
 * Queries are plain PromQL/MetricsQL range queries; the URL is configurable because the console
 * runs both inside the Compose network and on a developer machine.
 */
@ApplicationScoped
public class MetricsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    @ConfigProperty(name = "gtfsynq.ui.metrics.victoriametrics-url", defaultValue = "")
    String baseUrl;

    /** Whether a VictoriaMetrics URL is configured; a blank URL turns the metrics view off. */
    public boolean enabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * Runs a range query and returns one entry per resulting series.
     *
     * @param query PromQL/MetricsQL expression
     * @param lookback how far back the window starts
     * @param step resolution of the returned points
     */
    public List<Series> range(String query, Duration lookback, Duration step) {
        if (!enabled()) {
            throw new MetricsException("No VictoriaMetrics URL is configured");
        }
        var end = Instant.now();
        var start = end.minus(lookback);
        var uri = URI.create(stripTrailingSlash(baseUrl) + "/api/v1/query_range"
                + "?query=" + encode(query)
                + "&start=" + start.getEpochSecond()
                + "&end=" + end.getEpochSecond()
                + "&step=" + step.toSeconds());
        var request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new MetricsException("VictoriaMetrics answered HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new MetricsException("Could not reach VictoriaMetrics at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetricsException("Interrupted while querying VictoriaMetrics", e);
        }
    }

    /** Parses a {@code /api/v1/query_range} reply into series; package-private for tests. */
    static List<Series> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new MetricsException("VictoriaMetrics returned malformed JSON", e);
        }
        if (!"success".equals(root.path("status").asText())) {
            var error = root.path("error").asText("query failed");
            throw new MetricsException("VictoriaMetrics query failed: " + error);
        }

        var series = new ArrayList<Series>();
        for (var node : root.path("data").path("result")) {
            var labels = new LinkedHashMap<String, String>();
            node.path("metric")
                    .properties()
                    .forEach(
                            entry -> labels.put(entry.getKey(), entry.getValue().asText()));

            var points = new ArrayList<Point>();
            for (var value : node.path("values")) {
                var timestamp = value.path(0);
                var number = value.path(1).asText();
                if (!timestamp.isNumber() || number.isBlank()) {
                    continue;
                }
                try {
                    var parsed = Double.parseDouble(number);
                    if (Double.isFinite(parsed)) {
                        points.add(new Point(Instant.ofEpochSecond(timestamp.asLong()), parsed));
                    }
                } catch (NumberFormatException ignored) {
                    // VictoriaMetrics emits "+Inf" for some gaps; leave those out of the line.
                }
            }
            series.add(new Series(labels, points));
        }
        return series;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.replaceAll("/+$", "");
    }

    /** One value at one instant. */
    public record Point(Instant timestamp, double value) {}

    /**
     * One result series and the labels VictoriaMetrics returned for it.
     *
     * <p>Queries aggregate with {@code sum by (...)}, so the grouping label is the series name the
     * legend should show.
     */
    public record Series(Map<String, String> labels, List<Point> points) {

        private static final List<String> NAME_LABELS =
                List.of("outcome", "result", "op", "consumergroup", "application");

        public String name() {
            for (var label : NAME_LABELS) {
                var value = labels.get(label);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return labels.getOrDefault("__name__", "value");
        }
    }
}
