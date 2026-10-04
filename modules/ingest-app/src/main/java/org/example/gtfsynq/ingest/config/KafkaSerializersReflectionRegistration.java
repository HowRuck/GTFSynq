package org.example.gtfsynq.ingest.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Kafka instantiates the serializer classes named by {@code kafka.key.serializer} and
 * {@code kafka.value.serializer} reflectively, so they must survive native image trimming.
 */
@RegisterForReflection(targets = {StringSerializer.class, ByteArraySerializer.class})
class KafkaSerializersReflectionRegistration {}
