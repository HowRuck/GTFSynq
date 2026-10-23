package org.example.gtfsynq.ui.client;

import java.util.List;

/** A single page of rows plus the total row count used by the pager. */
public record PagedResult<T>(List<T> items, long total) {

    public PagedResult {
        items = List.copyOf(items);
    }
}
