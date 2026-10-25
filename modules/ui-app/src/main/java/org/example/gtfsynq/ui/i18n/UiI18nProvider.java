package org.example.gtfsynq.ui.i18n;

import com.vaadin.flow.i18n.DefaultI18NProvider;
import com.vaadin.flow.i18n.I18NProvider;
import com.vaadin.quarkus.annotation.VaadinServiceEnabled;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Locale;

/**
 * Translations for the console.
 *
 * <p>English, German and French are bundled. Adding a language means adding its locale here and a
 * {@code vaadin-i18n/translations_<lang>.properties} file next to the base bundle; the keys are
 * already shared by every view.
 *
 * <p>The provider is a CDI bean so the Quarkus Vaadin extension picks it up instead of falling back
 * to bundle discovery.
 */
@ApplicationScoped
@VaadinServiceEnabled
public class UiI18nProvider implements I18NProvider {

    private static final List<Locale> LOCALES = List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH);

    private final I18NProvider delegate = new DefaultI18NProvider(LOCALES, UiI18nProvider.class.getClassLoader());

    @Override
    public List<Locale> getProvidedLocales() {
        return LOCALES;
    }

    @Override
    public String getTranslation(String key, Locale locale, Object... params) {
        return delegate.getTranslation(key, locale, params);
    }
}
