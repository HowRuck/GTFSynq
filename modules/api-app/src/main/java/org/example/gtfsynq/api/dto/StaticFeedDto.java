package org.example.gtfsynq.api.dto;

import java.time.OffsetDateTime;

/** One row from {@code static_feeds}. */
public record StaticFeedDto(
        String feedId,
        String sha256,
        String bucket,
        String objectKey,
        String sourceUrl,
        long sizeBytes,
        OffsetDateTime ingestedAt,
        OffsetDateTime loadedAt) {}
