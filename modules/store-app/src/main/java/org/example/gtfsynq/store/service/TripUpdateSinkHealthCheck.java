package org.example.gtfsynq.store.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Reports a prolonged flush outage, i.e. transient failures that have kept retrying past the
 * configured threshold.
 *
 * <p>A quarantined batch does not make the sink unhealthy: it is bounded, isolated, and ingestion
 * has already resumed. Only a pipeline that cannot reach the database for a sustained period
 * flips this check, so alerts fire on the condition operators can act on.
 */
@Readiness
@ApplicationScoped
public class TripUpdateSinkHealthCheck implements HealthCheck {

    static final String NAME = "trip-update-sink";

    private final GtfsTripUpdateSink sink;

    public TripUpdateSinkHealthCheck(GtfsTripUpdateSink sink) {
        this.sink = sink;
    }

    @Override
    public HealthCheckResponse call() {
        var healthy = !sink.isUnhealthy();
        return HealthCheckResponse.builder()
                .name(NAME)
                .status(healthy)
                .withData("consecutiveFailures", sink.consecutiveFailures())
                .build();
    }
}
