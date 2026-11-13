package org.example.gtfsynq.ui.component.metrics;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
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
 * text, grid, ticks and font are applied by {@code metrics-chart-theme.ts}, which resolves them
 * from the Aura/Lumo theme tokens before a chart is built and refreshes them when the browser
 * switches between light and dark.
 */
@JsModule("./metrics-chart-theme.ts")
public class MetricsChart extends ChartContainer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String APPLY_THEME = "if (window.gtfsynqChartTheme) { window.gtfsynqChartTheme.apply(); }";

    /**
     * Line colours for the series inside one panel. Mid-tone hues stay legible on both the light and
     * dark chart background, where a theme token cannot be used because Chart.js paints a canvas.
     */
    private static final List<String> PALETTE = List.of(
            "#3b82f6", "#16a34a", "#d97706", "#dc2626", "#7c3aed", "#0891b2", "#db2777", "#64748b", "#65a30d",
            "#ea580c");

    public MetricsChart() {
        setWidthFull();
        setHeight("16rem");
    }

    /** Draws one line per series, aligned on their timestamps. */
    public void show(List<Series> series) {
        getElement().executeJs(APPLY_THEME);
        showChart(payload(series));
    }

    @Override
    public void showLoading() {
        var progress = new ProgressBar();
        progress.setIndeterminate(true);
        progress.setWidth("2.5rem");
        var label = new Span(getTranslation("metrics.loading"));
        label.addClassName("metrics-chart-state-detail");
        showState("metrics-chart-state-loading", progress, label);
    }

    @Override
    public void showFailed(String errorMsg) {
        var icon = VaadinIcon.EXCLAMATION_CIRCLE.create();
        icon.addClassName("metrics-chart-state-icon");
        var title = new Span(getTranslation("metrics.error.title"));
        title.addClassName("metrics-chart-state-title");
        var detail = new Span(errorMsg);
        detail.addClassName("metrics-chart-state-detail");
        showState("metrics-chart-state-error", icon, title, detail);
    }

    /** Replaces the chart with a neutral message instead of the error styling. */
    public void showEmpty(String message) {
        var icon = VaadinIcon.INFO_CIRCLE.create();
        icon.addClassName("metrics-chart-state-icon");
        var detail = new Span(message);
        detail.addClassName("metrics-chart-state-detail");
        showState("metrics-chart-state-empty", icon, detail);
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
        var singleSeries = series.size() == 1;
        for (var index = 0; index < series.size(); index++) {
            var entry = series.get(index);
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

            var color = PALETTE.get(index % PALETTE.size());
            dataset.put("borderColor", color);
            dataset.put("backgroundColor", translucent(color));
            dataset.put("pointBackgroundColor", color);
            dataset.put("pointBorderColor", color);
            dataset.put("pointRadius", 0);
            dataset.put("pointHoverRadius", 4);
            dataset.put("borderWidth", 2);
            dataset.put("tension", 0.3);
            dataset.put("fill", singleSeries);
        }

        // Everything else stays at the Chart.js defaults; only the fixed card height needs the
        // aspect ratio turned off so the chart follows the container instead of its width.
        var options = root.putObject("options");
        options.put("maintainAspectRatio", false);
        options.putObject("interaction").put("mode", "index").put("intersect", false);

        return MAPPER.writeValueAsString(root);
    }

    private void showState(String variant, Component... content) {
        clear();
        var layout = new VerticalLayout(content);
        layout.setPadding(false);
        layout.setSpacing(false);
        layout.setSizeFull();
        layout.setAlignItems(Alignment.CENTER);
        layout.setJustifyContentMode(JustifyContentMode.CENTER);
        layout.addClassName("metrics-chart-state");
        layout.addClassName(variant);
        add(layout);
    }

    private static String translucent(String hex) {
        var red = Integer.parseInt(hex.substring(1, 3), 16);
        var green = Integer.parseInt(hex.substring(3, 5), 16);
        var blue = Integer.parseInt(hex.substring(5, 7), 16);
        return "rgba(%d, %d, %d, 0.12)".formatted(red, green, blue);
    }
}
