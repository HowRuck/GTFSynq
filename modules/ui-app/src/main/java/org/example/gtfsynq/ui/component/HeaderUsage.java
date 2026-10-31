package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.example.gtfsynq.ui.client.MetricsClient;
import org.example.gtfsynq.ui.client.MetricsClient.Point;
import org.example.gtfsynq.ui.client.MetricsClient.Series;
import org.example.gtfsynq.ui.format.UiFormat;

/**
 * Compact CPU, memory and database usage in the navbar.
 *
 * <p>Values come from VictoriaMetrics, the same source as the metrics view, because that is the
 * only place where every app's JVM metrics and the Postgres/Podman exporter metrics meet. The
 * queries aggregate the whole stack: CPU is the sum of each Java process's share of one core,
 * memory is the sum of their heap usage, and the database row shows active connections, database
 * size and the TimescaleDB container's own CPU and memory. The header hides itself when no metrics
 * URL is configured, so a deployment without the monitoring stack does not show a permanent
 * placeholder.
 */
public class HeaderUsage extends HorizontalLayout {

    /**
     * One range query for every displayed value. {@code label_set} gives each aggregate a stable
     * series name, so a single request can populate the whole header instead of six round trips.
     * The container metrics are joined with {@code podman_container_info} because the exporter only
     * puts the container name on the info metric by default.
     */
    private static final String USAGE_QUERY = """
            label_set(sum(process.cpu.usage) * 100, 'metric', 'app_cpu')
            or label_set(sum(jvm.memory.used{area="heap"}), 'metric', 'app_memory')
            or label_set(sum(pg_stat_database_numbackends{datname="gtfsynq"}), 'metric', 'db_connections')
            or label_set(pg_database_size_bytes{datname="gtfsynq"}, 'metric', 'db_size')
            or label_set(sum(rate(podman_container_cpu_seconds_total[1m])
                   * on(id) group_left(name) podman_container_info{name="timescaledb"}) * 100, 'metric', 'db_cpu')
            or label_set(sum(podman_container_mem_usage_bytes
                   * on(id) group_left(name) podman_container_info{name="timescaledb"}), 'metric', 'db_memory')
            """;

    private static final Duration LOOKBACK = Duration.ofMinutes(1);

    private static final Duration STEP = Duration.ofSeconds(30);

    private static final long REFRESH_SECONDS = 30;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "header-usage-refresh");
        thread.setDaemon(true);
        return thread;
    });

    private final MetricsClient metrics;

    private final Span appCpu = value();

    private final Span appMemory = value();

    private final Span dbConnections = value();

    private final Span dbSize = value();

    private final Span dbCpu = value();

    private final Span dbMemory = value();

    private final AtomicBoolean loading = new AtomicBoolean(false);

    private volatile boolean attached;

    private ScheduledFuture<?> refresh;

    public HeaderUsage(MetricsClient metrics) {
        this.metrics = metrics;

        addClassName("app-usage");
        setPadding(false);
        setSpacing(false);
        setAlignItems(Alignment.CENTER);
        add(
                item("app.usage.cpu", "app.usage.cpu.title", appCpu),
                item("app.usage.memory", "app.usage.memory.title", appMemory),
                item("app.usage.db.connections", "app.usage.db.connections.title", dbConnections),
                item("app.usage.db.size", "app.usage.db.size.title", dbSize),
                item("app.usage.db.cpu", "app.usage.db.cpu.title", dbCpu),
                item("app.usage.db.memory", "app.usage.db.memory.title", dbMemory));
        setVisible(false);
    }

    private static Span value() {
        var value = new Span("—");
        value.addClassName("app-usage-value");
        return value;
    }

    private Span item(String labelKey, String titleKey, Span value) {
        var item = new Span();
        item.addClassName("app-usage-item");
        item.setTitle(getTranslation(titleKey));

        var label = new Span(getTranslation(labelKey));
        label.addClassName("app-usage-label");

        item.add(label, value);
        return item;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!metrics.enabled()) {
            setVisible(false);
            return;
        }

        attached = true;
        var ui = attachEvent.getUI();
        refreshNow(ui);
        refresh = SCHEDULER.scheduleWithFixedDelay(
                () -> refreshNow(ui), REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        attached = false;
        if (refresh != null) {
            refresh.cancel(false);
            refresh = null;
        }
        super.onDetach(detachEvent);
    }

    private void refreshNow(com.vaadin.flow.component.UI ui) {
        if (!attached || !loading.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            Map<String, Double> values = Map.of();
            try {
                values = latestValues(metrics.range(USAGE_QUERY, LOOKBACK, STEP));
            } catch (RuntimeException ignored) {
                // Keep the last good values; a monitoring blip must not disturb the console.
            }

            var latest = values;
            try {
                ui.access(() -> {
                    loading.set(false);
                    if (!attached) {
                        return;
                    }
                    apply(appCpu, latest.get("app_cpu"), "%.0f%%");
                    apply(appMemory, latest.get("app_memory"), null);
                    apply(dbConnections, latest.get("db_connections"), "%.0f");
                    apply(dbSize, latest.get("db_size"), null);
                    apply(dbCpu, latest.get("db_cpu"), "%.0f%%");
                    apply(dbMemory, latest.get("db_memory"), null);
                    if (!latest.isEmpty()) {
                        setVisible(true);
                    }
                });
            } catch (RuntimeException e) {
                // The session closed between the query and the push; the next component starts clean.
                loading.set(false);
            }
        });
    }

    private static void apply(Span target, Double value, String format) {
        if (value == null) {
            return;
        }
        if (format == null) {
            target.setText(UiFormat.bytes(Math.round(value)));
        } else {
            target.setText(String.format(Locale.ROOT, format, value));
        }
    }

    /** The newest point of every labelled metric series. */
    static Map<String, Double> latestValues(List<Series> series) {
        var values = new HashMap<String, Double>();
        for (var entry : series) {
            var metric = entry.labels().get("metric");
            if (metric == null) {
                continue;
            }
            var latest = latestPoint(entry.points());
            if (latest != null) {
                values.put(metric, latest);
            }
        }
        return values;
    }

    private static Double latestPoint(List<Point> points) {
        Point latest = null;
        for (var point : points) {
            if (latest == null || point.timestamp().isAfter(latest.timestamp())) {
                latest = point;
            }
        }
        return latest == null ? null : latest.value();
    }
}
