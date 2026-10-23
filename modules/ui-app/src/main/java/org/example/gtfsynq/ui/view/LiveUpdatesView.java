package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.PageHeader;
import org.example.gtfsynq.ui.component.PagedGrid;

/** Live trip updates, refreshed while the view is attached. */
@Route(value = "live", layout = MainLayout.class)
@PageTitle("Live updates · GTFSynq")
public class LiveUpdatesView extends VerticalLayout {

    private static final int POLL_INTERVAL_MS = 10_000;

    private final PagedGrid<TripUpdate> table;
    private Registration pollRegistration;

    @Inject
    public LiveUpdatesView(GtfsQueryClient client) {
        addClassName("page");
        setWidthFull();
        setPadding(false);
        setSpacing(true);

        table = new PagedGrid<>(client::tripUpdates, "lastSeenAt", SortDirection.DESCENDING, 25, Columns::tripUpdates);

        var feed = Filters.feed(client, table::setFeedId);
        var refresh = new Button("Refresh", event -> table.refresh());
        refresh.setIcon(VaadinIcon.REFRESH.create());

        add(
                PageHeader.create(
                        "Live trip updates",
                        "Refreshes every 10 seconds from the hot table while visible.",
                        feed,
                        refresh),
                table);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        var ui = attachEvent.getUI();
        ui.setPollInterval(POLL_INTERVAL_MS);
        pollRegistration = ui.addPollListener(event -> table.refresh());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }
}
