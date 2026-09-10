package org.example.gtfsynq.store.config;

import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints for the Kafka deserializers configured by class name in
 * {@code application.yaml}.
 *
 * <p>Spring AOT only registers the Kafka default String serde classes. The store
 * consumer binds {@code spring.kafka.consumer.value-deserializer} to
 * {@link ByteArrayDeserializer}, which is otherwise absent from the native image
 * and fails property binding with a {@code ClassNotFoundException} at startup.
 */
public class KafkaConsumerRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        MemberCategory[] memberCategories = {
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS
        };
        hints.reflection().registerType(ByteArrayDeserializer.class, memberCategories);
        hints.reflection().registerType(StringDeserializer.class, memberCategories);
    }
}
