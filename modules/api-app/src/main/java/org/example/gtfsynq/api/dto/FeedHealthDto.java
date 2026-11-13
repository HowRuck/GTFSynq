package org.example.gtfsynq.api.dto;

import java.util.Map;

/**
 * Feeds of one kind, counted by the state of their last poll.
 *
 * @param healthy polled successfully and recently
 * @param warning rate limited, or not polled for a while
 * @param error the last poll failed
 * @param unknown never polled
 */
public record FeedHealthDto(long healthy, long warning, long error, long unknown) {

    /**
     * Builds the counts from the database's state names, which are {@code healthy}, {@code warning},
     * {@code error} and {@code unknown}; a state with no feeds is simply absent.
     */
    public static FeedHealthDto of(Map<String, Long> counts) {
        return new FeedHealthDto(
                counts.getOrDefault("healthy", 0L),
                counts.getOrDefault("warning", 0L),
                counts.getOrDefault("error", 0L),
                counts.getOrDefault("unknown", 0L));
    }
}
