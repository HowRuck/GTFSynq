package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/** Page title, description and right-aligned filter controls. */
public final class PageHeader {

    private PageHeader() {}

    public static HorizontalLayout create(String title, String subtitle, Component... actions) {
        var heading = new H1(title);
        heading.addClassName("page-title");
        var description = new Span(subtitle);
        description.addClassName("page-subtitle");

        var text = new VerticalLayout(heading, description);
        text.setPadding(false);
        text.setSpacing(false);
        // Flow sizes a layout to 100% by default, which made the text consume the whole row and
        // pushed the actions onto a second line. Let it size to its content instead.
        text.setWidth("auto");

        var controls = new HorizontalLayout(actions);
        controls.setPadding(false);
        controls.setWrap(true);
        controls.setAlignItems(Alignment.END);
        controls.setJustifyContentMode(JustifyContentMode.END);
        // Keep the actions flush right, including when a narrow header wraps them under the text.
        controls.getStyle().set("margin-inline-start", "auto");
        controls.setFlexShrink(0);

        var header = new HorizontalLayout(text, controls);
        header.setPadding(false);
        header.setWrap(true);
        header.setWidthFull();
        header.setAlignItems(Alignment.END);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.setFlexGrow(1, text);
        return header;
    }
}
