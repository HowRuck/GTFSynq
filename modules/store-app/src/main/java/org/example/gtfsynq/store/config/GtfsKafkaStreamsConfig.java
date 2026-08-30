package org.example.gtfsynq.store.config;

import java.util.HashMap;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

/**
 * Configuration class for setting up Kafka Streams for GTFS data processing.
 */
@Configuration
@EnableConfigurationProperties(HotDataRetentionConfig.class)
public class GtfsKafkaStreamsConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.application.name:gtfs-stream-app}")
    private String applicationName;

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration defaultKafkaStreamsConfig() {
        var props = new HashMap<String, Object>();

        // Critical: Every Kafka Streams app needs a unique Application ID
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationName);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Default Serdes for the stream
        props.put(
                StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                Serdes.String().getClass().getName());
        props.put(
                StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                Serdes.ByteArray().getClass().getName());

        return new KafkaStreamsConfiguration(props);
    }

    @Bean
    public Serde<String> stringSerde() {
        return Serdes.String();
    }

    @Bean
    public Serde<byte[]> byteArraySerde() {
        return Serdes.ByteArray();
    }
}
