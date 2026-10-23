package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/** Page title, description and right-aligned filter controls. */
public final class PageHeader {

    private PageHeader() {}

    public static HorizontalLayout create(String title, String subtitle, Component... actions) {
        var heading = new H2(title);
        heading.addClassName("page-title");

        var description = new Span(subtitle);
        description.addClassName("muted");

        var text = new VerticalLayout(heading, description);
        text.setPadding(false);
        text.setSpacing(false);
        text.addClassName("page-heading");

        var controls = new HorizontalLayout(actions);
        controls.setPadding(false);
        controls.setSpacing(true);
        controls.setAlignItems(Alignment.END);
        controls.setJustifyContentMode(JustifyContentMode.END);
        controls.addClassName("page-actions");

        var header = new HorizontalLayout(text, controls);
        header.setWidthFull();
        header.setAlignItems(Alignment.END);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.addClassName("page-header");
        header.setFlexGrow(1, text);
        return header;
    }
}
