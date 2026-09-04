package org.example.gtfsynq.store.config;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for setting up plain Kafka consumer for GTFS data processing.
 *
 * <p>Kafka Streams is intentionally avoided: it pulls in RocksDB/JNI and
 * classes like {@code NoOpProcessorWrapper} that are not GraalVM native-friendly.
 * This workload is stateless (parse/filter/forward), so a plain listener suffices.
 */
@Configuration
@EnableConfigurationProperties(HotDataRetentionConfig.class)
public class GtfsKafkaStreamsConfig {

    @Bean
    public Serde<String> stringSerde() {
        return Serdes.String();
    }

    @Bean
    public Serde<byte[]> byteArraySerde() {
        return Serdes.ByteArray();
    }
}
