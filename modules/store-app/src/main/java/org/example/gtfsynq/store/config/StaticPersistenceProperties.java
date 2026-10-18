package org.example.gtfsynq.store.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Settings for writing validated static archives into the database.
 *
 * <p>Declared per app rather than shared for the same reason as {@link S3StorageProperties}: a
 * {@code @ConfigMapping} in a library module is not part of the application's CDI index.
 *
 * <pre>
 * gtfsynq.static.persistence.enabled={@code true}
 * gtfsynq.static.persistence.batch-size=5000
 * </pre>
 */
@ConfigMapping(prefix = "gtfsynq.static.persistence")
public interface StaticPersistenceProperties {

    /**
     * Master switch for the persistence stage; when false, a validated archive is validated and
     * dropped, leaving whatever revision is in the database untouched.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Rows handed from the archive reader to the database writer per batch.
     *
     * <p>Bigger batches mean fewer round trips and fewer statements, at the cost of holding the
     * batch in memory; 5000 keeps a multi-million row {@code stop_times.txt} flat in memory while
     * still amortising the round trip over many rows.
     */
    @WithName("batch-size")
    @WithDefault("5000")
    int batchSize();
}
