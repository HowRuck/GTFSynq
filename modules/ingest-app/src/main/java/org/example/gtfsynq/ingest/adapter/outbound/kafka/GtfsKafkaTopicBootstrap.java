package org.example.gtfsynq.ingest.adapter.outbound.kafka;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TopicExistsException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Creates the Kafka topics this app produces to, at startup, when the broker does not have them
 * <p>
 * Ingest produces every topic the pipeline uses, so it owns their creation. Each topic is created
 * on its own, so one failure does not hold back the others, and an existing topic is left as it
 * is. The work runs on a virtual thread so startup never waits for the broker; a consumer that
 * starts before its topic exists simply waits for it.
 */
@ApplicationScoped
@Slf4j
public class GtfsKafkaTopicBootstrap {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String bootstrapServers;

    private final List<String> topics;

    private final int partitions;

    private final int replicationFactor;

    public GtfsKafkaTopicBootstrap(
            @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers,
            @ConfigProperty(name = "gtfsynq.kafka.trip-updates-topic", defaultValue = "gtfs-trip-updates")
                    String tripUpdatesTopic,
            @ConfigProperty(name = "gtfsynq.kafka.static-feeds-topic", defaultValue = "gtfs-static-feeds")
                    String staticFeedsTopic,
            @ConfigProperty(name = "gtfsynq.kafka.feed-polls-topic", defaultValue = "gtfs-feed-polls")
                    String feedPollsTopic,
            @ConfigProperty(name = "gtfsynq.kafka.topic-partitions", defaultValue = "1") int partitions,
            @ConfigProperty(name = "gtfsynq.kafka.topic-replication-factor", defaultValue = "1")
                    int replicationFactor) {
        this.bootstrapServers = bootstrapServers;
        this.topics = List.of(tripUpdatesTopic, staticFeedsTopic, feedPollsTopic);
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
    }

    void createOnStartup(@Observes StartupEvent event) {
        Thread.startVirtualThread(this::create);
    }

    void create() {
        var config = Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try (var admin = Admin.create(config)) {
            var results = admin.createTopics(newTopics()).values();
            for (var entry : results.entrySet()) {
                awaitCreation(entry.getKey(), entry.getValue());
            }
        }
    }

    private List<NewTopic> newTopics() {
        return topics.stream()
                .map(name -> new NewTopic(name, partitions, (short) replicationFactor))
                .toList();
    }

    private void awaitCreation(String topic, KafkaFuture<Void> creation) {
        try {
            creation.get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            log.info("Created Kafka topic {} with {} partition(s) on {}", topic, partitions, bootstrapServers);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                log.debug("Kafka topic {} already exists on {}", topic, bootstrapServers);
                return;
            }
            log.warn("Could not create Kafka topic {} on {}: {}", topic, bootstrapServers, e.getMessage());
        } catch (TimeoutException e) {
            log.warn("Timed out creating Kafka topic {} on {}", topic, bootstrapServers);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while creating Kafka topic {}", topic);
        }
    }
}
