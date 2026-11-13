package org.example.gtfsynq.ui.component.overview;

import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.time.Duration;
import java.time.Instant;
import org.example.gtfsynq.shared.protocol.PollStatus;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.component.UiComponents;
import org.example.gtfsynq.ui.format.UiFormat;

/**
 * One feed on the overview: its last poll outcome, when it was loaded and when it was last polled.
 * <p>
 * A feed that has stopped polling reports no error at all, so a last poll older than
 * {@link #STALE_AFTER_POLL} is flagged even when that poll succeeded.
 */
public class FeedHealthTile extends Card {

    static final Duration STALE_AFTER_POLL = Duration.ofMinutes(15);

    private static final long MINUTE_LABEL_LIMIT_SECONDS = 120;

    private static final long HOUR_LABEL_LIMIT_SECONDS = 7200;

    public FeedHealthTile(StaticFeed feed, Instant now) {
        addClassName("feed-tile");
        addClassName(health(feed, now));

        var name = new Span(feed.getFeedId());
        name.addClassName("feed-tile-name");
        var kind = new Span(getTranslation("feed.kind." + kindKey(feed)));
        kind.addClassName("feed-tile-kind");
        var header = new Div(name, kind);
        header.addClassName("feed-tile-header");

        var loaded = line(getTranslation("feed.tile.loaded", UiFormat.dateTime(feed.getLoadedAt())));
        var content = new VerticalLayout(header, UiComponents.pollStatus(this, feed), loaded, polledLine(feed, now));
        content.setPadding(false);
        content.setSpacing(false);
        add(content);
    }

    /**
     * CSS class for the tile's colour: error when the last poll failed, warning when rate limited or
     * not polled recently, healthy when the last poll succeeded, and unknown before any poll.
     */
    static String health(StaticFeed feed, Instant now) {
        if (!feed.hasPollStatus()) {
            return "feed-tile-unknown";
        }
        var status = feed.getPollStatus();
        if (status == PollStatus.POLL_STATUS_HTTP_ERROR || status == PollStatus.POLL_STATUS_ERROR) {
            return "feed-tile-error";
        }
        if (status == PollStatus.POLL_STATUS_RATE_LIMITED || isStale(feed, now)) {
            return "feed-tile-warning";
        }
        return "feed-tile-ok";
    }

    static boolean isStale(StaticFeed feed, Instant now) {
        return feed.hasLastPolledAt() && pollAge(feed, now).compareTo(STALE_AFTER_POLL) > 0;
    }

    private static Duration pollAge(StaticFeed feed, Instant now) {
        return Duration.between(UiFormat.instant(feed.getLastPolledAt()), now);
    }

    private Span polledLine(StaticFeed feed, Instant now) {
        if (!feed.hasLastPolledAt()) {
            return line(getTranslation("feed.tile.never"));
        }
        var age = pollAge(feed, now);
        if (isStale(feed, now)) {
            var stale = line(getTranslation("feed.tile.stale", age(age)));
            stale.addClassName("feed-tile-stale");
            return stale;
        }
        return line(getTranslation("feed.tile.polled", age(age)));
    }

    private String age(Duration age) {
        var seconds = Math.max(0, age.toSeconds());
        if (seconds < MINUTE_LABEL_LIMIT_SECONDS) {
            return getTranslation("feed.age.seconds", seconds);
        }
        if (seconds < HOUR_LABEL_LIMIT_SECONDS) {
            return getTranslation("feed.age.minutes", seconds / 60);
        }
        return getTranslation("feed.age.hours", seconds / 3600);
    }

    private static String kindKey(StaticFeed feed) {
        return feed.getKind().isEmpty() ? "static" : feed.getKind();
    }

    private static Span line(String text) {
        var span = new Span(text);
        span.addClassName("feed-tile-line");
        return span;
    }
}
