package org.example.gtfsynq.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.VaadinSession;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.example.gtfsynq.ui.client.MetricsClient;
import org.example.gtfsynq.ui.component.metrics.HeaderUsage;
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

    @Inject
    public MainLayout(MetricsClient metrics) {
        setPrimarySection(Section.NAVBAR);

        var brandName = new RouterLink(getTranslation("app.name"), OverviewView.class);
        brandName.addClassName("app-brand");
        var brand = new HorizontalLayout(brandName, new Badge(getTranslation("app.badge")));
        brand.setPadding(false);
        brand.setAlignItems(Alignment.CENTER);

        addTab(getTranslation("nav.overview"), "", OverviewView.class);
        addTab(getTranslation("nav.feeds"), "feeds", FeedsView.class);
        addTab(getTranslation("nav.routes"), "routes", RoutesView.class);
        addTab(getTranslation("nav.stops"), "stops", StopsView.class);
        addTab(getTranslation("nav.trips"), "trips", TripsView.class);
        addTab(getTranslation("nav.live"), "live", LiveUpdatesView.class);
        addTab(getTranslation("nav.metrics"), "metrics", MetricsView.class);

        var navbar = new HorizontalLayout(brand, tabs, new HeaderUsage(metrics), languageSwitcher(), clock());
        navbar.addClassName("app-navbar");
        navbar.setPadding(false);
        navbar.setWidthFull();
        navbar.setAlignItems(Alignment.CENTER);
        // The tabs take the free space so the switcher and clock stay at the far right.
        navbar.expand(tabs);

        addToNavbar(navbar);
    }

    /**
     * Lets the user override the browser's language. The trigger is an icon-only button so it stays
     * quiet next to the navigation; the context menu lists the bundled languages and marks the one
     * in use. The choice is stored on the session and the page is reloaded so every label built by
     * the current UI is rebuilt in the new locale.
     */
    private Component languageSwitcher() {
        var button = new Button();
        button.setIcon(VaadinIcon.GLOBE.create());
        button.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        button.addClassName("app-language");
        button.setAriaLabel(getTranslation("language.label"));
        button.setTooltipText(getTranslation("language.label"));

        var menu = new ContextMenu(button);
        menu.setOpenOnClick(true);

        var current = VaadinSession.getCurrent().getLocale();
        for (var locale : LANGUAGES) {
            var item = menu.addItem(locale.getDisplayLanguage(locale), event -> {
                if (locale.equals(VaadinSession.getCurrent().getLocale())) {
                    return;
                }
                VaadinSession.getCurrent().setLocale(locale);
                UI.getCurrent().getPage().reload();
            });
            item.setCheckable(true);
            item.setChecked(locale.equals(current));
        }
        return button;
    }

    /**
     * A wall clock that ticks in the browser: formatting there avoids a server round trip every
     * second and shows the user's own time zone, matching the timestamps in the tables.
     */
    private static Span clock() {
        var clock = new Span();
        clock.addClassName("app-clock");
        // A timer role tells assistive technology this text changes on its own; it is not content
        // the user is expected to read in sequence.
        clock.getElement().setAttribute("role", "timer");
        clock.getElement().setAttribute("aria-live", "off");
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
        var link = new RouterLink(label, view);
        link.addClassName("nav-link");
        var tab = new Tab(link);
        tabs.add(tab);
        routes.put(tab, new NavItem(path));
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        var path = event.getLocation().getPath();
        routes.entrySet().stream()
                .filter(entry -> entry.getValue().path().equals(path))
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(tabs::setSelectedTab);
    }

    private record NavItem(String path) {}
}
