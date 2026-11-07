package org.example.gtfsynq.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.shared.ui.Transport;
import com.vaadin.flow.theme.aura.Aura;

/**
 * Host page for the console.
 *
 * <p>Server push backs the live view's polling. It streams over Server-Sent Events instead of a
 * WebSocket so it survives proxies and firewalls that block WebSocket connections; the transport
 * is experimental and needs the {@code ssePushTransport} feature flag, enabled in {@code
 * vaadin-featureflags.properties}. Aura is loaded explicitly because Vaadin 25 applies no theme
 * without an annotation. The color scheme follows the operating system so Aura's light-dark()
 * tokens and the console's dark palette are both live; see {@code aura-theme.css}.
 */
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("styles.css")
@ColorScheme(ColorScheme.Value.SYSTEM)
@Push(transport = Transport.SERVER_SENT_EVENTS)
public class AppShell implements AppShellConfigurator {}
