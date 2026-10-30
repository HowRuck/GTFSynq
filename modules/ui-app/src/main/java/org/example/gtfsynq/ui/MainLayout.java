package org.example.gtfsynq.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.server.VaadinSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.example.gtfsynq.ui.view.FeedsView;
import org.example.gtfsynq.ui.view.LiveUpdatesView;
import org.example.gtfsynq.ui.view.MetricsView;
import org.example.gtfsynq.ui.view.OverviewView;
import org.example.gtfsynq.ui.view.RoutesView;
import org.example.gtfsynq.ui.view.StopsView;
import org.example.gtfsynq.ui.view.TripsView;

/** Top navigation and router shell shared by all views. */
public class MainLayout extends AppLayout implements AfterNavigationObserver {

    private static final List<Locale> LANGUAGES = List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH);

    private final Tabs tabs = new Tabs();
    private final Map<Tab, NavItem> routes = new LinkedHashMap<>();

    private Tab activeTab;

    public MainLayout() {
        setPrimarySection(Section.NAVBAR);

        var brand = new HorizontalLayout(new Span(getTranslation("app.name")), new Badge(getTranslation("app.badge")));
        brand.setPadding(false);
        brand.setAlignItems(Alignment.CENTER);

        addTab(getTranslation("nav.overview"), "", OverviewView.class);
        addTab(getTranslation("nav.feeds"), "feeds", FeedsView.class);
        addTab(getTranslation("nav.routes"), "routes", RoutesView.class);
        addTab(getTranslation("nav.stops"), "stops", StopsView.class);
        addTab(getTranslation("nav.trips"), "trips", TripsView.class);
        addTab(getTranslation("nav.live"), "live", LiveUpdatesView.class);
        addTab(getTranslation("nav.metrics"), "metrics", MetricsView.class);

        tabs.addSelectedChangeListener(event -> {
            var tab = event.getSelectedTab();
            if (tab == null || tab == activeTab) {
                return;
            }
            activeTab = tab;
            UI.getCurrent().navigate(routes.get(tab).view());
        });

        var navbar = new HorizontalLayout(brand, tabs, languageSwitcher(), clock());
        navbar.setPadding(false);
        navbar.setWidthFull();
        navbar.setAlignItems(Alignment.CENTER);
        // The tabs take the free space so the switcher and clock stay at the far right.
        navbar.expand(tabs);

        addToNavbar(navbar);
    }

    /**
     * Lets the user override the browser's language. The choice is stored on the session and the
     * page is reloaded so every label built by the current UI is rebuilt in the new locale.
     */
    private Select<Locale> languageSwitcher() {
        var select = new Select<Locale>();
        select.setItems(LANGUAGES);
        select.setItemLabelGenerator(locale -> locale.getDisplayLanguage(locale));
        select.setValue(currentLanguage());
        select.setAriaLabel(getTranslation("language.label"));
        select.addValueChangeListener(event -> {
            if (event.getValue() == null) {
                return;
            }
            VaadinSession.getCurrent().setLocale(event.getValue());
            UI.getCurrent().getPage().reload();
        });
        return select;
    }

    /** Maps a browser locale such as {@code de-DE} onto one of the bundled languages. */
    private static Locale currentLanguage() {
        var current = UI.getCurrent().getLocale();
        return LANGUAGES.stream()
                .filter(locale -> locale.getLanguage().equals(current.getLanguage()))
                .findFirst()
                .orElse(Locale.ENGLISH);
    }

    /**
     * A wall clock that ticks in the browser: formatting there avoids a server round trip every
     * second and shows the user's own time zone, matching the timestamps in the tables.
     */
    private static Span clock() {
        var clock = new Span();
        clock.addClassName("app-clock");
        clock.getElement().executeJs("""
                const clock = this;
                const formatter = new Intl.DateTimeFormat(undefined, {
                    hour: '2-digit',
                    minute: '2-digit',
                    second: '2-digit'
                });
                const tick = () => clock.textContent = formatter.format(new Date());
                tick();
                setInterval(tick, 1000);
                """);
        return clock;
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
