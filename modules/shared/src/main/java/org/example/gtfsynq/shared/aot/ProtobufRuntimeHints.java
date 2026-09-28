package org.example.gtfsynq.shared.aot;

import com.google.protobuf.ExtensionRegistry;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints for Protobuf message parsing and serialisation.
 *
 * <p>{@code ExtensionRegistryFactory} probes for the full (non-Lite) Protobuf runtime via
 * reflection and then reflectively invokes {@link ExtensionRegistry#getEmptyRegistry()} /
 * {@code newInstance()}. That happens in {@code AbstractParser}'s static initialiser, which
 * runs the first time <em>any</em> generated message is instantiated. The reachability
 * metadata shipped with {@code protobuf-java} does not cover this path, so without these
 * hints the app dies at runtime with a {@code MissingReflectionRegistrationError}.
 *
 * <p>This lives in {@code :shared} because that is where the generated messages are
 * defined, and is discovered through {@code META-INF/spring/aot.factories} so that every
 * application depending on the protos picks it up. Registering it per-application instead
 * means each new app rediscovers the same failure the first time it touches a message.
 */
public class ProtobufRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(ExtensionRegistry.class, MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
