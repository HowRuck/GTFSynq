package org.example.gtfsynq.ui.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.i18n.I18NProvider;
import com.vaadin.quarkus.annotation.VaadinServiceEnabled;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

@QuarkusTest
class UiI18nProviderTest {

    @Inject
    @VaadinServiceEnabled
    I18NProvider provider;

    @Test
    void providesEnglishAndResolvesTranslations() {
        assertEquals(List.of(Locale.ENGLISH), provider.getProvidedLocales());
        assertEquals("Static feeds", provider.getTranslation("nav.feeds", Locale.ENGLISH));
        assertEquals("Stops · GTFSynq", provider.getTranslation("document.title", Locale.ENGLISH, "Stops"));
    }
}
