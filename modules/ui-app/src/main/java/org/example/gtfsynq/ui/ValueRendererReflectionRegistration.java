package org.example.gtfsynq.ui;

import com.vaadin.flow.data.renderer.BasicRenderer;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;
import com.vaadin.flow.data.renderer.LocalDateRenderer;
import com.vaadin.flow.data.renderer.LocalDateTimeRenderer;
import com.vaadin.flow.data.renderer.NumberRenderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Since Vaadin 25.4.0-beta2, {@code LitRenderer} decides whether a renderer subclass declares its
 * own template by walking the class hierarchy with {@code getDeclaredMethods()}. In a native image
 * that returns nothing for classes that are not registered for reflection, so grid columns fail
 * with "ComponentRenderer has no getTemplateExpression()". Register the renderers the grids use.
 */
@RegisterForReflection(
        targets = {
            LitRenderer.class,
            BasicRenderer.class,
            ComponentRenderer.class,
            TextRenderer.class,
            NumberRenderer.class,
            LocalDateRenderer.class,
            LocalDateTimeRenderer.class
        })
class ValueRendererReflectionRegistration {}
