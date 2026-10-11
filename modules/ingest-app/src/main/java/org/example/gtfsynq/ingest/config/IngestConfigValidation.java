package org.example.gtfsynq.ingest.config;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.constraint.CharSequenceConstraint;
import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validator;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates the ingest configuration at startup with <a href="https://github.com/making/yavi">YAVI</a>.
 * <p>
 * Config-mapping Bean Validation (via {@code quarkus-hibernate-validator}) is deliberately not used.
 * It installs a global SmallRye config validator that reflectively reads every config-mapping
 * interface method, and those interface methods are not registered for reflective invocation in
 * native images built with GraalVM 25.1+, so the application fails to start
 * (see <a href="https://github.com/quarkusio/quarkus/issues/57050">quarkusio/quarkus#57050</a>).
 * YAVI is reflection-free and has no such integration.
 */
@ApplicationScoped
public class IngestConfigValidation {

    /** A single feed URL extracted from the config tree so YAVI can validate it in isolation. */
    private record FeedUrl(String value) {}

    private static final Validator<FeedUrl> URL_VALIDATOR = ValidatorBuilder.<FeedUrl>of()
            .constraint(FeedUrl::value, "url", c -> c.notBlank().url())
            .build();

    private static final Validator<S3StorageProperties> STORAGE_VALIDATOR = ValidatorBuilder.<S3StorageProperties>of()
            .constraint(S3StorageProperties::bucket, "bucket", CharSequenceConstraint::notBlank)
            .build();

    private final GtfsProperties gtfsProperties;
    private final S3StorageProperties storageProperties;

    public IngestConfigValidation(GtfsProperties gtfsProperties, S3StorageProperties storageProperties) {
        this.gtfsProperties = gtfsProperties;
        this.storageProperties = storageProperties;
    }

    void validate(@Observes StartupEvent event) {
        var problems = new ArrayList<String>();

        collect(problems, "gtfsynq.storage.s3.bucket", STORAGE_VALIDATOR.validate(storageProperties));

        gtfsProperties.sources().forEach((name, source) -> {
            var prefix = "gtfs.sources." + name;
            collect(
                    problems,
                    prefix + ".static-config.url",
                    URL_VALIDATOR.validate(new FeedUrl(source.staticConfig().url())));

            for (var entry : source.staticConfig().fileUrls().entrySet()) {
                collect(
                        problems,
                        prefix + ".static-config.file-urls." + entry.getKey(),
                        URL_VALIDATOR.validate(new FeedUrl(entry.getValue())));
            }

            for (var url : source.realtimeConfig().urls()) {
                collect(problems, prefix + ".realtime-config.urls", URL_VALIDATOR.validate(new FeedUrl(url)));
            }
        });

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid ingest configuration:\n - " + String.join("\n - ", problems));
        }
    }

    private static void collect(List<String> problems, String property, ConstraintViolations violations) {
        for (var violation : violations) {
            problems.add(violation.rename(ignored -> property).message());
        }
    }
}
