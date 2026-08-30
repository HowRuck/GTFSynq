package org.example.gtfsynq.store.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "gtfsynq.retention")
public record HotDataRetentionConfig(
        @DefaultValue("1h") Duration hours,
        @DefaultValue("15") Integer rateMinutes) {}
