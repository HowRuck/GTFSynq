package org.example.gtfsynq.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.example.gtfsynq.ui.view.FeedsView;
import org.example.gtfsynq.ui.view.LiveUpdatesView;
import org.example.gtfsynq.ui.view.OverviewView;
import org.example.gtfsynq.ui.view.RoutesView;
import org.example.gtfsynq.ui.view.StopsView;
import org.example.gtfsynq.ui.view.TripsView;

/** Top navigation and router shell shared by all views. */
public class MainLayout extends AppLayout implements AfterNavigationObserver {

    private final Tabs tabs = new Tabs();
    private final Map<Tab, NavItem> routes = new LinkedHashMap<>();

    private Tab activeTab;

    public MainLayout() {
        setPrimarySection(Section.NAVBAR);

        var brand = new HorizontalLayout(new Span("GTFSynq"), new Badge("console"));
        brand.setPadding(false);
        brand.setAlignItems(Alignment.CENTER);

        addTab("Overview", "", OverviewView.class);
        addTab("Static feeds", "feeds", FeedsView.class);
        addTab("Routes", "routes", RoutesView.class);
        addTab("Stops", "stops", StopsView.class);
        addTab("Trips", "trips", TripsView.class);
        addTab("Live updates", "live", LiveUpdatesView.class);

        tabs.addSelectedChangeListener(event -> {
            var tab = event.getSelectedTab();
            if (tab == null || tab == activeTab) {
                return;
            }
            activeTab = tab;
            UI.getCurrent().navigate(routes.get(tab).view());
        });

        addToNavbar(brand, tabs);
    }

    private void addTab(String label, String path, Class<? extends Component> view) {
        var tab = new Tab(label);
        tabs.add(tab);
        routes.put(tab, new NavItem(view, path));
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        var path = event.getLocation().getPath();
        routes.entrySet().stream()
                .filter(entry -> entry.getValue().path().equals(path))
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(tab -> {
                    activeTab = tab;
                    tabs.setSelectedTab(tab);
                });
    }

    private record NavItem(Class<? extends Component> view, String path) {}
}
