package org.example.gtfsynq.ui.client;

/**
 * One page request for a table view.
 *
 * <p>Blank filters mean "all"; the backend applies the same three-character minimum search rule as
 * the REST API.
 */
public record PageQuery(String feedId, String search, int page, int size, String sort, boolean descending) {

    public PageQuery {
        feedId = feedId == null || feedId.isBlank() ? null : feedId;
        search = search == null ? "" : search.trim();
        sort = sort == null ? "" : sort;
    }

    public String direction() {
        return descending ? "desc" : "asc";
    }
}
