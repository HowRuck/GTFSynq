package org.example.gtfsynq.ui.component.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import org.example.gtfsynq.shared.protocol.PollStatus;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.junit.jupiter.api.Test;

class FeedHealthTileTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Test
    void successfulRecentPollIsHealthy() {
        assertEquals(
                "feed-tile-ok", FeedHealthTile.health(polled(PollStatus.POLL_STATUS_OK, NOW.minusSeconds(30)), NOW));
    }

    @Test
    void rateLimitedPollIsWarning() {
        assertEquals(
                "feed-tile-warning",
                FeedHealthTile.health(polled(PollStatus.POLL_STATUS_RATE_LIMITED, NOW.minusSeconds(30)), NOW));
    }

    @Test
    void failedPollIsErrorEvenWhenRecent() {
        assertEquals(
                "feed-tile-error",
                FeedHealthTile.health(polled(PollStatus.POLL_STATUS_HTTP_ERROR, NOW.minusSeconds(30)), NOW));
    }

    @Test
    void successfulPollOlderThanTheStaleLimitIsWarning() {
        var justInside = NOW.minus(FeedHealthTile.STALE_AFTER_POLL).plusSeconds(1);
        var justOutside = NOW.minus(FeedHealthTile.STALE_AFTER_POLL).minusSeconds(1);

        assertEquals("feed-tile-ok", FeedHealthTile.health(polled(PollStatus.POLL_STATUS_OK, justInside), NOW));
        assertEquals("feed-tile-warning", FeedHealthTile.health(polled(PollStatus.POLL_STATUS_OK, justOutside), NOW));
    }

    @Test
    void feedWithoutAnyPollIsUnknown() {
        assertEquals("feed-tile-unknown", FeedHealthTile.health(StaticFeed.getDefaultInstance(), NOW));
    }

    @Test
    void stalenessNeedsAPollTime() {
        assertEquals(false, FeedHealthTile.isStale(StaticFeed.getDefaultInstance(), NOW));
    }

    private static StaticFeed polled(PollStatus status, Instant at) {
        return StaticFeed.newBuilder()
                .setFeedId("gtfs-de")
                .setPollStatus(status)
                .setLastPolledAt(
                        Timestamp.newBuilder().setSeconds(at.getEpochSecond()).build())
                .build();
    }
}
