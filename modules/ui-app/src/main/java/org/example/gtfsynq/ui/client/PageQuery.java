package org.example.gtfsynq.ui.client;

/**
 * One page request for a table view.
 *
 * <p>Blank filters mean "all"; the backend applies the same three-character minimum search rule as
 * the REST API. The optional enum filters are independent of the view that issues the query: each
 * client method forwards only those its backend endpoint understands.
 */
public record PageQuery(
        String feedId,
        String search,
        String agency,
        Integer routeType,
        Integer directionId,
        Integer locationType,
        int page,
        int size,
        String sort,
        boolean descending) {

    public PageQuery {
        feedId = feedId == null || feedId.isBlank() ? null : feedId;
        search = search == null ? "" : search.trim();
        sort = sort == null ? "" : sort;
    }

    public String direction() {
        return descending ? "desc" : "asc";
    }
}
