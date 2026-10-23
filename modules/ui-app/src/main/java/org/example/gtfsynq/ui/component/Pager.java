package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import java.util.List;

/** Page-size selector and previous/next controls below a table. */
public class Pager extends HorizontalLayout {

    private static final List<Integer> PAGE_SIZES = List.of(10, 25, 50, 100);

    private final Listener listener;
    private final Button previous = new Button("Previous");
    private final Button next = new Button("Next");
    private final ComboBox<Integer> pageSize = new ComboBox<>();
    private final Span range = new Span();
    private final Span pageLabel = new Span();

    private int currentPage;
    private int currentSize = 25;
    private long total;
    private boolean updating;

    public Pager(Listener listener) {
        this.listener = listener;

        setWidthFull();
        setPadding(false);
        setSpacing(true);
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.BETWEEN);
        addClassName("table-pager");

        range.addClassName("muted");
        pageLabel.addClassName("muted");

        previous.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
        next.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
        previous.addClickListener(event -> listener.pageChanged(currentPage - 1));
        next.addClickListener(event -> listener.pageChanged(currentPage + 1));

        pageSize.setItems(PAGE_SIZES);
        pageSize.setItemLabelGenerator(value -> value + " / page");
        pageSize.setAllowCustomValue(false);
        pageSize.setWidth("9rem");
        pageSize.addValueChangeListener(event -> {
            if (!updating && event.getValue() != null) {
                listener.pageSizeChanged(event.getValue());
            }
        });

        var controls = new HorizontalLayout(pageSize, previous, pageLabel, next);
        controls.setSpacing(true);
        controls.setPadding(false);
        controls.setAlignItems(Alignment.CENTER);

        add(range, controls);
    }

    public void update(int page, int size, long total) {
        updating = true;
        try {
            this.currentPage = Math.max(page, 0);
            this.currentSize = size;
            this.total = Math.max(total, 0);

            if (pageSize.getValue() == null || pageSize.getValue() != size) {
                pageSize.setValue(size);
            }

            long totalPages = totalPages();
            long from = this.total == 0 ? 0 : (long) this.currentPage * this.currentSize + 1;
            long to = Math.min(this.total, (long) (this.currentPage + 1) * this.currentSize);

            range.setText(from + "-" + to + " of " + this.total);
            pageLabel.setText((this.currentPage + 1) + " / " + totalPages);
            previous.setEnabled(this.currentPage > 0);
            next.setEnabled(this.currentPage + 1 < totalPages);
        } finally {
            updating = false;
        }
    }

    private long totalPages() {
        return total == 0 ? 1 : (total + currentSize - 1) / currentSize;
    }

    /** Receives user actions; the owning grid reloads the affected page. */
    public interface Listener {

        void pageChanged(int page);

        void pageSizeChanged(int size);
    }
}
