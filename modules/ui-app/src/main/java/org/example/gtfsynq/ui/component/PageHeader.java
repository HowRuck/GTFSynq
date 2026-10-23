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
        var text = new VerticalLayout(new H2(title), new Span(subtitle));
        text.setPadding(false);
        text.setSpacing(false);

        var controls = new HorizontalLayout(actions);
        controls.setPadding(false);
        controls.setAlignItems(Alignment.END);
        controls.setJustifyContentMode(JustifyContentMode.END);

        var header = new HorizontalLayout(text, controls);
        header.setPadding(false);
        header.setWidthFull();
        header.setAlignItems(Alignment.END);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.setFlexGrow(1, text);
        return header;
    }
}
