package org.example.gtfsynq.shared.model;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import java.time.Instant;

public record FeedEntityWithMetadata(FeedEntity entity, long type, Instant feedTs) {}
