package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.html.Span;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import org.example.gtfsynq.ui.client.MetricsClient.Point;
import org.example.gtfsynq.ui.client.MetricsClient.Series;
import software.xdev.vaadin.chartjs.ChartContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Chart.js line chart fed from VictoriaMetrics range-query series.
 *
 * <p>The payload is built with Jackson's tree model rather than a POJO: the chart model library
 * serialises POJOs reflectively, which would need reflection registrations in the native image.
 */
public class MetricsChart extends ChartContainer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public MetricsChart() {
        setWidthFull();
        setHeight("18rem");
    }

    /** Draws one line per series, aligned on their timestamps. */
    public void show(List<Series> series) {
        showChart(payload(series));
    }

    /** Replaces the chart with a neutral message instead of the error styling. */
    public void showEmpty(String message) {
        clear();
        var span = new Span(message);
        span.getStyle().set("color", "var(--vaadin-text-color-secondary)");
        add(span);
    }

    /** The Chart.js configuration for the given series; package-private for tests. */
    static String payload(List<Series> series) {
        var timestamps = series.stream()
                .flatMap(entry -> entry.points().stream())
                .map(Point::timestamp)
                .distinct()
                .sorted()
                .toList();

        var root = MAPPER.createObjectNode();
        root.put("type", "line");

        var data = root.putObject("data");
        var labels = data.putArray("labels");
        timestamps.forEach(timestamp -> labels.add(TIME.format(timestamp.atZone(ZoneId.systemDefault()))));

        var datasets = data.putArray("datasets");
        for (var entry : series) {
            var byTimestamp = new HashMap<Instant, Double>();
            entry.points().forEach(point -> byTimestamp.put(point.timestamp(), point.value()));

            var dataset = datasets.addObject();
            dataset.put("label", entry.name());
            var values = dataset.putArray("data");
            for (var timestamp : timestamps) {
                var value = byTimestamp.get(timestamp);
                if (value == null || !Double.isFinite(value)) {
                    values.addNull();
                } else {
                    values.add(value);
                }
            }
            dataset.put("borderWidth", 2);
            dataset.put("pointRadius", 0);
            dataset.put("tension", 0.25);
            dataset.put("fill", false);
        }

        var options = root.putObject("options");
        options.put("responsive", true);
        options.put("maintainAspectRatio", false);
        options.put("animation", false);
        options.putObject("interaction").put("mode", "index").put("intersect", false);
        options.putObject("plugins").putObject("legend").put("display", true).put("position", "bottom");
        var scales = options.putObject("scales");
        scales.putObject("x").putObject("ticks").put("maxTicksLimit", 8);
        scales.putObject("y").put("beginAtZero", true);

        return toJson(root);
    }

    private static String toJson(ObjectNode root) {
        return MAPPER.writeValueAsString(root);
    }
}
