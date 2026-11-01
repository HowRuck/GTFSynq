package org.example.gtfsynq.ingest.adapter.inbound.protobuf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class GtfsScannerTest {

    private static final int TRIP_UPDATE_TYPE = 3;

    private static GtfsScanner.ScanResult scan(byte[] entity) throws Exception {
        return GtfsScanner.scanEntity(CodedInputStream.newInstance(entity));
    }

    @Test
    void prefersTheTripDescriptorKey() throws Exception {
        var entity = FeedEntity.newBuilder()
                .setId("entity-1")
                .setTripUpdate(TripUpdate.newBuilder()
                        .setTrip(TripDescriptor.newBuilder()
                                .setTripId("trip-1")
                                .setStartDate("20261101")
                                .build())
                        .build())
                .build();

        var result = scan(entity.toByteArray());

        assertEquals("trip-1:20261101", result.id());
        assertEquals(TRIP_UPDATE_TYPE, result.type());
    }

    @Test
    void fallsBackToTheEntityIdWhenTheTripDescriptorHasNoTripId() throws Exception {
        // HSL publishes route, start date and start time, but no trip id at all.
        var entity = FeedEntity.newBuilder()
                .setId("7990512197997432")
                .setTripUpdate(TripUpdate.newBuilder()
                        .setTrip(TripDescriptor.newBuilder()
                                .setRouteId("31M1")
                                .setStartDate("20261101")
                                .setStartTime("16:26:00")
                                .build())
                        .build())
                .build();

        var result = scan(entity.toByteArray());

        assertEquals("7990512197997432", result.id());
        assertEquals(TRIP_UPDATE_TYPE, result.type());
    }

    @Test
    void fallsBackWhenTheDescriptorComesBeforeTheEntityId() throws Exception {
        var tripUpdate = TripUpdate.newBuilder()
                .setTrip(TripDescriptor.newBuilder().setRouteId("31M1").build())
                .build();

        var bytes = new ByteArrayOutputStream();
        var out = CodedOutputStream.newInstance(bytes);
        // Field order is not guaranteed on the wire, so the entity id may arrive last.
        out.writeMessage(3, tripUpdate);
        out.writeString(1, "entity-9");
        out.flush();

        var result = scan(bytes.toByteArray());

        assertEquals("entity-9", result.id());
        assertEquals(TRIP_UPDATE_TYPE, result.type());
    }

    @Test
    void leavesTheEntityTypeUnknownForANonEntityPayload() throws Exception {
        var result = scan(new byte[0]);

        assertNull(result.id());
        assertEquals(-1, result.type());
    }
}
