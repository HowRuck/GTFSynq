package org.example.gtfsynq.ui.client;

/** Loads one page of rows for a table view. */
@FunctionalInterface
public interface PageLoader<T> {

    PagedResult<T> load(PageQuery query);
}
