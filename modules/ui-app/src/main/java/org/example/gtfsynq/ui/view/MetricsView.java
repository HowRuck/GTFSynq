package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.MetricsClient;
import org.example.gtfsynq.ui.client.MetricsClient.Series;
import org.example.gtfsynq.ui.component.MetricsChart;
import org.example.gtfsynq.ui.component.PageHeader;

/**
 * Charts a few cluster-wide metrics from VictoriaMetrics.
 *
 * <p>Every app pushes its metrics to the monitoring stack over OTLP, so VictoriaMetrics is the one
 * place where ingest, validation, Kafka and JVM values from all apps can be read. The queries below
 * are the same expressions the provisioned Grafana dashboards use.
 */
@Route(value = "metrics", layout = MainLayout.class)
public class MetricsView extends VerticalLayout implements HasDynamicTitle {

    private static final Duration LOOKBACK = Duration.ofHours(1);
    private static final Duration STEP = Duration.ofMinutes(1);

    private final MetricsClient client;
    private final List<Panel> panels;

    private boolean attached;
    private boolean loading;

    @Inject
    public MetricsView(MetricsClient client) {
        this.client = client;

        panels = List.of(
                panel("metrics.panel.ingest", "sum by (outcome) (rate(gtfs.static.ingest[5m]))"),
                panel("metrics.panel.validation", "sum by (result) (rate(gtfs.static.validation.feeds[5m]))"),
                panel("metrics.panel.kafkaLag", "sum by (consumergroup) (kafka_consumergroup_lag)"),
                panel("metrics.panel.heap", "sum by (application) (jvm.memory.used{area=\"heap\"})"));

        var refresh = new Button(getTranslation("metrics.refresh"), event -> getUI().ifPresent(this::load));
        refresh.setIcon(VaadinIcon.REFRESH.create());

        var grid = new Div();
        grid.addClassName("metrics-grid");
        panels.forEach(panel -> grid.add(panel.card()));

        setSizeFull();
        add(
                PageHeader.create(
                        getTranslation("page.metrics.title"), getTranslation("page.metrics.subtitle"), refresh),
                grid);
        setFlexGrow(1, grid);
    }

    private Panel panel(String titleKey, String query) {
        var chart = new MetricsChart();
        var card = new Card();
        card.setTitle(getTranslation(titleKey));
        card.setWidthFull();
        card.add(chart);
        return new Panel(query, chart, card);
    }

    /**
     * Runs every panel's query off the session-lock thread and renders the replies in one
     * {@code ui.access()} call, so the charts update together instead of one by one.
     */
    private void load(UI ui) {
        if (loading) {
            return;
        }
        if (!client.enabled()) {
            panels.forEach(panel -> panel.chart().showFailed(getTranslation("metrics.disabled")));
            return;
        }

        loading = true;
        panels.forEach(panel -> panel.chart().showLoading());
        CompletableFuture.runAsync(() -> {
            var outcomes = new ArrayList<Outcome>(panels.size());
            for (var panel : panels) {
                try {
                    var series = client.range(panel.query(), LOOKBACK, STEP);
                    outcomes.add(
                            series.stream().allMatch(entry -> entry.points().isEmpty())
                                    ? new Outcome(List.of(), null)
                                    : new Outcome(series, null));
                } catch (RuntimeException e) {
                    outcomes.add(new Outcome(null, message(e)));
                }
            }

            ui.access(() -> {
                loading = false;
                if (!attached) {
                    return;
                }
                for (var index = 0; index < panels.size(); index++) {
                    var chart = panels.get(index).chart();
                    var outcome = outcomes.get(index);
                    if (outcome.error() != null) {
                        chart.showFailed(getTranslation("metrics.error.load", outcome.error()));
                    } else if (outcome.series().isEmpty()) {
                        chart.showEmpty(getTranslation("metrics.empty"));
                    } else {
                        chart.show(outcome.series());
                    }
                }
            });
        });
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        attached = true;
        load(attachEvent.getUI());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        attached = false;
        super.onDetach(detachEvent);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.metrics.title"));
    }

    private record Panel(String query, MetricsChart chart, Card card) {}

    /** A panel's result: either its series or the failure message. */
    private record Outcome(List<Series> series, String error) {}
}
