package org.example.gtfsynq.ingest.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class GtfsPropertiesRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        MemberCategory[] memberCategories = {
            MemberCategory.DECLARED_FIELDS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS
        };
        hints.reflection().registerType(GtfsProperties.class, memberCategories);
        hints.reflection().registerType(GtfsProperties.FeedSource.class, memberCategories);
        hints.reflection().registerType(GtfsProperties.StaticConfig.class, memberCategories);
        hints.reflection().registerType(GtfsProperties.RealtimeConfig.class, memberCategories);
        hints.reflection()
                .registerType(org.example.gtfsynq.ingest.config.enums.GtfsStaticFeedFile.class, memberCategories);
    }
}
