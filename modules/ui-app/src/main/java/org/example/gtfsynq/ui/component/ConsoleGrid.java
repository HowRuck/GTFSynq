package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import java.util.function.Consumer;

/**
 * Shared configuration for every console table.
 *
 * <p>Row height and cell font size are left to the Aura theme on purpose: Aura has no dense grid
 * variant ({@link GridVariant#LUMO_COMPACT} is Lumo-only), so the only way to change density is a
 * per-table override, which would make one table the odd one out. Sharing the column sizing and the
 * row striping here keeps the tables structurally identical as well.
 */
abstract class ConsoleGrid<T> extends Grid<T> {

    ConsoleGrid(Consumer<Grid<T>> columnConfigurer) {
        setWidthFull();
        setMultiSort(false);
        setSelectionMode(SelectionMode.NONE);
        addThemeVariants(GridVariant.ROW_STRIPES);
        columnConfigurer.accept(this);
        sizeColumnsToContent();
    }

    /** Sizes each column to its content, then lets the columns share any remaining width. */
    private void sizeColumnsToContent() {
        getColumns().forEach(column -> column.setAutoWidth(true).setFlexGrow(1).setResizable(true));
    }
}
