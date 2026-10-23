package org.example.gtfsynq.ui.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.Timestamp;
import java.time.Instant;
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
        assertEquals("N/A", UiFormat.elideId(null));
        assertEquals("N/A", UiFormat.elideId(" "));
    }

    @Test
    void rendersTimestampsInCentralEuropeanSummerTime() {
        assertEquals("2026-07-01 12:00:00", UiFormat.dateTime(timestamp("2026-07-01T10:00:00Z")));
        assertEquals("12:00:00", UiFormat.time(timestamp("2026-07-01T10:00:00Z")));
    }

    @Test
    void followsTheDaylightSavingSwitchToCentralEuropeanTime() {
        assertEquals("2026-01-15 11:00:00", UiFormat.dateTime(timestamp("2026-01-15T10:00:00Z")));
    }

    @Test
    void derivesEventTimesInCentralEuropeanSummerTime() {
        var scheduled = timestamp("2026-07-01T10:00:00Z");

        assertEquals("12:02:00", UiFormat.eventTime(null, scheduled, 120));
    }

    private static Timestamp timestamp(String instant) {
        return Timestamp.newBuilder()
                .setSeconds(Instant.parse(instant).getEpochSecond())
                .build();
    }
}
