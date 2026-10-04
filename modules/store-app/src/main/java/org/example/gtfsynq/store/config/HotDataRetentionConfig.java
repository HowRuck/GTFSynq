package org.example.gtfsynq.store.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

@ConfigMapping(prefix = "gtfsynq.retention")
public interface HotDataRetentionConfig {

    @WithDefault("1h")
    Duration hours();

    @WithDefault("15m")
    Duration rate();
}
