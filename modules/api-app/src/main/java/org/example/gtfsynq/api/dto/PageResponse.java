package org.example.gtfsynq.api.dto;

import java.util.List;

/**
 * A single page of rows plus the metadata the UI needs to render a pager.
 *
 * <p>{@code page} is zero based to match the table component used by the frontend.
 */
public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {}
