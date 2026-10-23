package org.example.gtfsynq.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.aura.Aura;

/**
 * Host page for the console.
 *
 * <p>Server push backs the live view's polling, and Aura is loaded explicitly because Vaadin 25
 * applies no theme without an annotation.
 */
@StyleSheet(Aura.STYLESHEET)
@Push
public class AppShell implements AppShellConfigurator {}
