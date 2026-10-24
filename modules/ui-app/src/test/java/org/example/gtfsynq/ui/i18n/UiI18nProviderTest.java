package org.example.gtfsynq.ui.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.vaadin.flow.i18n.I18NProvider;
import com.vaadin.quarkus.annotation.VaadinServiceEnabled;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;

@QuarkusTest
class UiI18nProviderTest {

    @Inject
    @VaadinServiceEnabled
    I18NProvider provider;

    @Test
    void providesEnglishGermanAndFrench() {
        assertEquals(List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH), provider.getProvidedLocales());
    }

    @Test
    void resolvesEnglish() {
        assertEquals("Static Feeds", provider.getTranslation("nav.feeds", Locale.ENGLISH));
        assertEquals("Stops · GTFSynq", provider.getTranslation("document.title", Locale.ENGLISH, "Stops"));
    }

    @Test
    void resolvesGerman() {
        assertEquals("Statische Feeds", provider.getTranslation("nav.feeds", Locale.GERMAN));
        assertEquals("Haltestellen · GTFSynq",
                provider.getTranslation("document.title", Locale.GERMAN, "Haltestellen"));
        assertEquals("Statische Feeds", provider.getTranslation("nav.feeds", Locale.GERMANY));
    }

    @Test
    void resolvesFrench() {
        assertEquals("Flux statiques", provider.getTranslation("nav.feeds", Locale.FRENCH));
        assertEquals("Arrêts · GTFSynq", provider.getTranslation("document.title", Locale.FRENCH, "Arrêts"));
        assertEquals("Flux statiques", provider.getTranslation("nav.feeds", Locale.FRANCE));
    }

    @Test
    void translatedBundlesCoverEveryBaseKey() throws IOException {
        var baseKeys = load("translations.properties").stringPropertyNames();
        assertEquals(baseKeys, load("translations_de.properties").stringPropertyNames());
        assertEquals(baseKeys, load("translations_fr.properties").stringPropertyNames());
    }

    private static Properties load(String file) throws IOException {
        var resource = "vaadin-i18n/" + file;
        try (var stream = UiI18nProviderTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            var properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
