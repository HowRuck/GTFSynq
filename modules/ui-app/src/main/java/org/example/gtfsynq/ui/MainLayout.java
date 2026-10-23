package org.example.gtfsynq.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.RouterLink;
import java.util.ArrayList;
import java.util.List;
import org.example.gtfsynq.ui.component.UiComponents;
import org.example.gtfsynq.ui.view.FeedsView;
import org.example.gtfsynq.ui.view.LiveUpdatesView;
import org.example.gtfsynq.ui.view.OverviewView;
import org.example.gtfsynq.ui.view.RoutesView;
import org.example.gtfsynq.ui.view.StopsView;
import org.example.gtfsynq.ui.view.TripsView;

/** Top navigation and router shell shared by all views. */
public class MainLayout extends AppLayout implements AfterNavigationObserver {

    private final List<NavEntry> entries = new ArrayList<>();

    public MainLayout() {
        setPrimarySection(Section.NAVBAR);

        var brand = new HorizontalLayout();
        brand.setPadding(false);
        brand.setSpacing(true);
        brand.setAlignItems(Alignment.CENTER);
        brand.addClassName("brand");
        brand.add(new Span("GTFSynq"), UiComponents.badge("console"));

        var nav = new HorizontalLayout();
        nav.setPadding(false);
        nav.setSpacing(false);
        nav.setAlignItems(Alignment.CENTER);
        nav.addClassName("app-nav");

        addLink(nav, "Overview", "", OverviewView.class);
        addLink(nav, "Static feeds", "feeds", FeedsView.class);
        addLink(nav, "Routes", "routes", RoutesView.class);
        addLink(nav, "Stops", "stops", StopsView.class);
        addLink(nav, "Trips", "trips", TripsView.class);
        addLink(nav, "Live updates", "live", LiveUpdatesView.class);

        addToNavbar(brand, nav);
    }

    private void addLink(HorizontalLayout nav, String text, String path, Class<? extends Component> view) {
        var link = new RouterLink(text, view);
        link.addClassName("nav-link");
        nav.add(link);
        entries.add(new NavEntry(link, path));
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        var path = event.getLocation().getPath();
        for (var entry : entries) {
            entry.link.getElement().getClassList().set("active", entry.path.equals(path));
        }
    }

    private record NavEntry(RouterLink link, String path) {}
}
