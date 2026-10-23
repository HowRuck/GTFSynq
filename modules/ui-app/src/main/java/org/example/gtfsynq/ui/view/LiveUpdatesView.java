package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.LazyGrid;
import org.example.gtfsynq.ui.component.PageHeader;

/** Live trip updates, refreshed while the view is attached. */
@Route(value = "live", layout = MainLayout.class)
public class LiveUpdatesView extends VerticalLayout implements HasDynamicTitle {

    private static final int POLL_INTERVAL_MS = 10_000;

    private final LazyGrid<TripUpdate> table;
    private Registration pollRegistration;

    @Inject
    public LiveUpdatesView(GtfsQueryClient client) {
        table = new LazyGrid<>(client::tripUpdates, "lastSeenAt", SortDirection.DESCENDING, Columns::tripUpdates);

        var feed = Filters.feed(client, getTranslation("filter.feed.placeholder"), table::setFeedId);
        var refresh = new Button(getTranslation("live.refresh"), event -> table.refresh());
        refresh.setIcon(VaadinIcon.REFRESH.create());

        add(
                PageHeader.create(
                        getTranslation("page.live.title"),
                        getTranslation("page.live.subtitle", POLL_INTERVAL_MS / 1000),
                        feed,
                        refresh),
                table);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.live.title"));
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
