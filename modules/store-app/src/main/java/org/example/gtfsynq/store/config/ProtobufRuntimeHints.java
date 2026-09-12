package org.example.gtfsynq.store.config;

import com.google.protobuf.ExtensionRegistry;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints for Protobuf message parsing.
 *
 * <p>{@code ExtensionRegistryFactory} probes for the full (non-Lite) Protobuf
 * runtime via reflection and then reflectively invokes
 * {@link ExtensionRegistry#getEmptyRegistry()} / {@code newInstance()}.
 * The reachability metadata shipped with {@code protobuf-java} does not cover
 * this path, so parsing a generated message fails in the native image with a
 * {@code MissingReflectionRegistrationError}.
 */
public class ProtobufRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection()
                .registerType(ExtensionRegistry.class, MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
