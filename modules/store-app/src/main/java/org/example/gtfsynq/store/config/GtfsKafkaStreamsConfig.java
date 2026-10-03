package org.example.gtfsynq.store.config;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for setting up plain Kafka consumer for GTFS data processing
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
