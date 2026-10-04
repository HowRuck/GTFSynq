package org.example.gtfsynq.store.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Kafka instantiates the deserializer classes named by
 * {@code mp.messaging.incoming.*.key.deserializer} and
 * {@code mp.messaging.incoming.*.value.deserializer} reflectively, so they must
 * survive native image trimming.
 */
@RegisterForReflection(targets = {StringDeserializer.class, ByteArrayDeserializer.class})
class KafkaDeserializersReflectionRegistration {}
