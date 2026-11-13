package org.example.gtfsynq.ui.component.overview;

import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.example.gtfsynq.ui.format.UiFormat;

/**
 * Overview KPI card that counts up to each new value and states the change since the previous one.
 * <p>
 * The count-up runs in the browser so it can check the reduced-motion preference; the server only
 * sends the target value.
 */
public class StatCard extends Card {

    private static final String COUNT_UP = """
        const from = $0;
        const to = $1;
        const finalText = $2;
        const token = (this.countUpToken || 0) + 1;
        this.countUpToken = token;
        if (from === to || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            this.textContent = finalText;
            return;
        }
        const start = performance.now();
        const frame = (now) => {
            if (token !== this.countUpToken) {
                return;
            }
            const t = Math.min(1, (now - start) / 450);
            const eased = 1 - Math.pow(1 - t, 3);
            this.textContent = t < 1
                ? Math.round(from + (to - from) * eased).toLocaleString()
                : finalText;
            if (t < 1) {
                requestAnimationFrame(frame);
            }
        };
        requestAnimationFrame(frame);
        """;

    private final Span amount = new Span(UiFormat.NOT_AVAILABLE);

    private final Span detail = new Span();

    private final Div change = new Div();

    private Long shown;

    public StatCard(String label) {
        var title = new Span(label);
        title.addClassName("stat-label");
        amount.addClassName("stat-value");
        detail.addClassName("stat-detail");
        detail.setVisible(false);
        change.addClassName("stat-change");
        change.setVisible(false);

        var content = new VerticalLayout(title, amount, detail, change);
        content.setPadding(false);
        content.setSpacing(false);
        add(content);
    }

    /**
     * Shows a count. The first value appears at once; later values count up from the previous one
     * and the change line is updated.
     *
     * @param value the count, used for the change since the previous value
     * @param formatted the count as displayed once the count-up ends
     */
    public void show(long value, String formatted) {
        if (shown == null) {
            amount.setText(formatted);
        } else {
            amount.getElement().executeJs(COUNT_UP, (double) shown, (double) value, formatted);
            showChange(value - shown);
        }
        shown = value;
    }

    /**
     * Shows a text value, such as a relative time, coloured by its tone; no count-up or change line.
     *
     * @param text the value as displayed
     * @param tone {@code ok}, {@code warning} or {@code error}, which colours the value
     */
    public void showText(String text, String tone) {
        amount.setText(text);
        amount.setClassName("stat-value stat-tone-" + tone);
        change.setVisible(false);
    }

    /**
     * Shows a line of detail under the value, such as a breakdown; an empty text hides it.
     *
     * @param text the detail line
     */
    public void showDetail(String text) {
        detail.setText(text);
        detail.setVisible(!text.isEmpty());
    }

    /** A change arrow sized to the change text, not to the icon's default box. */
    private static Icon arrow(VaadinIcon kind) {
        var icon = kind.create();
        icon.addClassName("stat-change-icon");
        return icon;
    }

    private void showChange(long difference) {
        change.setVisible(true);
        change.removeAll();
        if (difference > 0) {
            change.setClassName("stat-change stat-change-up");
            change.add(
                    arrow(VaadinIcon.ARROW_UP),
                    new Span(getTranslation("overview.delta.up", UiFormat.count(difference))));
        } else if (difference < 0) {
            change.setClassName("stat-change stat-change-down");
            change.add(
                    arrow(VaadinIcon.ARROW_DOWN),
                    new Span(getTranslation("overview.delta.down", UiFormat.count(-difference))));
        } else {
            change.setClassName("stat-change stat-change-none");
            change.add(new Span(getTranslation("overview.delta.none")));
        }
    }
}
