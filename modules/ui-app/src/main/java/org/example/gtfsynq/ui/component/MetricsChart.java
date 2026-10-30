package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.dependency.JsModule;
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

/**
 * A Chart.js line chart fed from VictoriaMetrics range-query series.
 *
 * <p>The payload is built with Jackson's tree model rather than a POJO: the chart model library
 * serialises POJOs reflectively, which would need reflection registrations in the native image.
 *
 * <p>Chart.js draws on a canvas, so it cannot inherit CSS custom properties. The defaults for
 * text, grid and font are applied by {@code metrics-chart-theme.js}, which resolves them from the
 * Aura/Lumo theme tokens before a chart is built and refreshes them when the browser switches
 * between light and dark.
 */
@JsModule("./metrics-chart-theme.js")
public class MetricsChart extends ChartContainer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String APPLY_THEME = "if (window.gtfsynqChartTheme) { window.gtfsynqChartTheme.apply(); }";

    public MetricsChart() {
        setWidthFull();
        setHeight("18rem");
    }

    /** Draws one line per series, aligned on their timestamps. */
    public void show(List<Series> series) {
        getElement().executeJs(APPLY_THEME);
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
        }

        // Everything else stays at the Chart.js defaults; only the fixed card height needs the
        // aspect ratio turned off so the chart follows the container instead of its width.
        root.putObject("options").put("maintainAspectRatio", false);

        return MAPPER.writeValueAsString(root);
    }
}
