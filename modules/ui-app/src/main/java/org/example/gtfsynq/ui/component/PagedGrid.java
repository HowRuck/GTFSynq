package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.PageLoader;
import org.example.gtfsynq.ui.client.PageQuery;
import org.example.gtfsynq.ui.client.PagedResult;

/**
 * A card containing a server-backed table and a pager.
 *
 * <p>Paging is explicit (one gRPC request per page) rather than lazy: the backend query language is
 * page based, and this keeps the totals shown by the pager authoritative.
 */
public class PagedGrid<T> extends VerticalLayout {

    private static final int MIN_SEARCH_LENGTH = 3;

    private final Grid<T> grid = new Grid<>();
    private final PageLoader<T> loader;
    private final Pager pager;
    private final Div errorBanner = new Div();

    private final String defaultSort;
    private final SortDirection defaultDirection;

    private String feedId;
    private String search;
    private String sort;
    private SortDirection direction;
    private int page;
    private int pageSize;
    private boolean ready;

    public PagedGrid(
            PageLoader<T> loader,
            String defaultSort,
            SortDirection defaultDirection,
            int initialPageSize,
            Consumer<Grid<T>> columnConfigurer) {
        this.loader = loader;
        this.defaultSort = defaultSort;
        this.defaultDirection = defaultDirection;
        this.sort = defaultSort;
        this.direction = defaultDirection;
        this.pageSize = initialPageSize;

        setWidthFull();
        setPadding(false);
        setSpacing(false);

        grid.setWidthFull();
        grid.setAllRowsVisible(true);
        grid.setMultiSort(false);
        grid.setSelectionMode(Grid.SelectionMode.NONE);
        grid.addThemeVariants(GridVariant.ROW_STRIPES);
        columnConfigurer.accept(grid);

        grid.getColumns().stream()
                .filter(column -> Objects.equals(defaultSort, column.getKey()))
                .findFirst()
                .ifPresent(column -> grid.sort(List.of(new GridSortOrder<>(column, defaultDirection))));
        grid.addSortListener(event -> {
            if (!ready) {
                return;
            }
            var order = event.getSortOrder().stream().findFirst().orElse(null);
            if (order == null || order.getSorted().getKey() == null) {
                sort = defaultSort;
                direction = defaultDirection;
            } else {
                sort = order.getSorted().getKey();
                direction = order.getDirection();
            }
            page = 0;
            reload();
        });

        pager = new Pager(new Pager.Listener() {
            @Override
            public void pageChanged(int requestedPage) {
                if (requestedPage >= 0 && requestedPage != page) {
                    page = requestedPage;
                    reload();
                }
            }

            @Override
            public void pageSizeChanged(int requestedSize) {
                if (requestedSize != pageSize) {
                    pageSize = requestedSize;
                    page = 0;
                    reload();
                }
            }
        });

        errorBanner.addClassName("error-banner");
        errorBanner.setVisible(false);

        var card = new Div(grid, pager);
        card.addClassName("card");
        card.setWidthFull();

        add(errorBanner, card);

        ready = true;
        reload();
    }

    /** Applies a feed filter and returns to the first page. */
    public void setFeedId(String value) {
        var normalized = value == null || value.isBlank() ? null : value;
        if (Objects.equals(feedId, normalized)) {
            return;
        }
        feedId = normalized;
        page = 0;
        reload();
    }

    /** Applies a search term; shorter terms are ignored, matching the REST API. */
    public void setSearch(String value) {
        var term = value == null ? "" : value.trim();
        var normalized = term.length() >= MIN_SEARCH_LENGTH ? term : null;
        if (Objects.equals(search, normalized)) {
            return;
        }
        search = normalized;
        page = 0;
        reload();
    }

    /** Reloads the current page without touching filters or sorting. */
    public void refresh() {
        reload();
    }

    private void reload() {
        try {
            var result = loadPage();
            if (result.items().isEmpty() && page > 0) {
                page = 0;
                result = loadPage();
            }
            grid.setItems(result.items());
            pager.update(page, pageSize, result.total());
            showError(null);
        } catch (DataAccessException e) {
            grid.setItems(List.of());
            pager.update(page, pageSize, 0);
            showError(e.getMessage());
        }
    }

    private PagedResult<T> loadPage() {
        return loader.load(new PageQuery(feedId, search, page, pageSize, sort, direction == SortDirection.DESCENDING));
    }

    private void showError(String message) {
        if (message == null || message.isBlank()) {
            errorBanner.setText("");
            errorBanner.setVisible(false);
            return;
        }
        errorBanner.setText("Could not load data: " + message);
        errorBanner.setVisible(true);
    }
}
