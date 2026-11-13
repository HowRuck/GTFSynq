package org.example.gtfsynq.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One row from {@code static_feeds} with the agencies that feed describes, or, when {@code kind} is
 * {@code realtime}, one row from {@code feed_poll_state}.
 */
public record StaticFeedDto(
        String feedId,
        String sha256,
        String bucket,
        String objectKey,
        String sourceUrl,
        String objectEtag,
        String sourceEtag,
        String sourceLastModified,
        long sizeBytes,
        OffsetDateTime ingestedAt,
        OffsetDateTime loadedAt,
        List<String> agencyNames,
        String kind,
        String pollStatus) {}
