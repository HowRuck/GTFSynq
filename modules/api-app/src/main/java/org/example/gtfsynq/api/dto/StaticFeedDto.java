package org.example.gtfsynq.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** One row from {@code static_feeds}, with the agencies that feed describes. */
public record StaticFeedDto(
        String feedId,
        String sha256,
        String bucket,
        String objectKey,
        String sourceUrl,
        long sizeBytes,
        OffsetDateTime ingestedAt,
        OffsetDateTime loadedAt,
        List<String> agencyNames) {}
