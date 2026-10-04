package org.example.gtfsynq.ingest.config;

import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;

/**
 * Configuration for Kafka producer.
 * <p>
 * The producer is built from the {@code kafka.*} keys in {@code application.properties},
 * which carry the bootstrap servers, serializers and batching/timeout tuning, so no
 * producer settings are hard-coded here.
 */
@Singleton
public class KafkaProducerConfig {

    /**
     * Kafka producer for sending byte array messages.
     *
     * @param config the default Kafka broker configuration injected by Quarkus
     * @return Kafka producer
     */
    @Produces
    @Singleton
    public KafkaProducer<String, byte[]> kafkaProducer(@Identifier("default-kafka-broker") Map<String, Object> config) {
        var producerConfig = new HashMap<String, Object>();

        for (var name : ProducerConfig.configNames()) {
            if (config.containsKey(name)) {
                producerConfig.put(name, config.get(name));
            }
        }

        return new KafkaProducer<>(producerConfig);
    }

    void dispose(@Disposes KafkaProducer<String, byte[]> producer) {
        producer.close();
    }

    /**
     * Exposes the producer's Micrometer client metrics.
     * <p>
     * Quarkus only instruments the Kafka clients it creates itself, and a
     * {@code KafkaClientMetrics} is a {@code MeterBinder} that Quarkus binds
     * automatically, so simply exposing it is enough to get producer-side meters.
     *
     * @param producer the configured producer
     * @return client metrics for the producer
     */
    @Produces
    @Singleton
    public KafkaClientMetrics kafkaProducerMetrics(KafkaProducer<String, byte[]> producer) {
        return new KafkaClientMetrics(producer);
    }
}
