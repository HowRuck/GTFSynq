package org.example.gtfsynq.ui.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UiFormatTest {

    @Test
    void elidesIdsLongerThanTheColumnLimit() {
        var longId = "BRA:ServiceJourney:6228_260630061449169_1060";

        var elided = UiFormat.elideId(longId);

        assertEquals(32, elided.length());
        assertEquals("BRA:ServiceJourney:6228_2606300…", elided);
    }

    @Test
    void keepsShortIdentifiersAndUnsetValues() {
        assertEquals("62_1060", UiFormat.elideId("62_1060"));
        assertEquals("—", UiFormat.elideId(null));
        assertEquals("—", UiFormat.elideId(" "));
    }
}
