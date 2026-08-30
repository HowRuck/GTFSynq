package org.example.gtfsynq.ingest.adapter.inbound.protobuf;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans GTFS-RT entities and extracts the stable ID and entity type.
 */
@Slf4j
public class GtfsScanner {

    // GTFS-RT feed entity field numbers
    private static final int FIELD_FEED_ENTITY = 1;
    private static final int FIELD_TRIP_UPDATE = 3;
    private static final int FIELD_VEHICLE_POSITION = 4;
    private static final int FIELD_ALERT = 5;

    // Nested trip descriptor field numbers
    private static final int FIELD_TRIP_DESCRIPTOR = 1;
    private static final int FIELD_TRIP_ID = 1;
    private static final int FIELD_START_DATE = 3;

    /**
     * Represents the result of scanning a GTFS-RT entity, including the stable ID
     * and entity type.
     */
    public record ScanResult(String id, int type) {}

    /**
     * Scans a GTFS-RT entity and returns the result, including the stable ID and
     * entity type.
     *
     * @param cis the CodedInputStream to read from
     * @return the scan result, including the stable ID and entity type
     * @throws IOException if an error occurs while reading from the stream
     */
    public static ScanResult scanEntity(CodedInputStream cis) throws IOException {
        String stableId = null;
        int entityType = -1;

        while (!cis.isAtEnd()) {
            var tag = cis.readTag();
            var fieldNumber = WireFormat.getTagFieldNumber(tag);

            // Switch on the field number to determine how to read the value
            switch (fieldNumber) {
                // Read the stable ID from the feed entity field
                case FIELD_FEED_ENTITY -> stableId = cis.readString();
                // Read the stable ID from the nested trip descriptor
                case FIELD_TRIP_UPDATE, FIELD_VEHICLE_POSITION -> {
                    entityType = fieldNumber;
                    stableId = parseNestedTripDescriptor(cis);
                }
                // Skip the alert field, stable ID is taken from the entity itself
                case FIELD_ALERT -> {
                    entityType = fieldNumber;
                    cis.skipField(tag);
                }
                default -> cis.skipField(tag);
            }
        }

        return new ScanResult(stableId, entityType);
    }

    /**
     * Finds the stable ID of a GTFS-RT entity by reading the nested trip
     * descriptor.
     *
     * @param cis the CodedInputStream to read from
     * @return the stable ID of the entity, or null if not found
     * @throws IOException if an error occurs while reading from the stream
     */
    public static String findEntityId(CodedInputStream cis) throws IOException {
        String stableId = null;

        // Loop through the fields of the entity, looking for the trip descriptor
        while (!cis.isAtEnd()) {
            var tag = cis.readTag();
            var fieldNumber = WireFormat.getTagFieldNumber(tag);

            switch (fieldNumber) {
                case FIELD_TRIP_UPDATE, FIELD_VEHICLE_POSITION -> stableId = parseNestedTripDescriptor(cis);
                default -> cis.skipField(tag);
            }
        }

        return stableId;
    }

    /**
     * Parses the nested trip descriptor and returns the stable ID.
     *
     * @param cis the CodedInputStream to read from
     * @return the stable ID of the trip, or null if not found
     * @throws IOException if an error occurs while reading from the stream
     */
    public static String parseNestedTripDescriptor(CodedInputStream cis) throws IOException {
        // Read the length of the trip descriptor and set the limit
        var length = cis.readRawVarint32();
        var oldLimit = cis.pushLimit(length);

        String tripId = null;
        String startDate = null;

        try {
            // Read the trip descriptor fields
            while (!cis.isAtEnd()) {
                var tag = cis.readTag();
                var fieldNumber = WireFormat.getTagFieldNumber(tag);

                if (fieldNumber != FIELD_TRIP_DESCRIPTOR) {
                    cis.skipField(tag);
                    continue;
                }

                // Limit the read to the length of the nested trip descriptor
                var descriptorLength = cis.readRawVarint32();
                var descriptorLimit = cis.pushLimit(descriptorLength);

                try {
                    // Read the nested trip descriptor fields
                    while (!cis.isAtEnd()) {
                        var innerTag = cis.readTag();
                        var innerFieldNumber = WireFormat.getTagFieldNumber(innerTag);

                        // Switch on the inner field number to determine how to read the value
                        switch (innerFieldNumber) {
                            // Read the trip ID from the nested trip descriptor
                            case FIELD_TRIP_ID -> tripId = cis.readString();
                            // Read the start date from the nested trip descriptor
                            case FIELD_START_DATE -> startDate = cis.readString();
                            default -> cis.skipField(innerTag);
                        }
                    }
                } finally {
                    // Pop the limit to restore the stream position
                    cis.popLimit(descriptorLimit);
                }
            }
        } finally {
            // Pop the limit to restore the stream position
            cis.popLimit(oldLimit);
        }

        if (tripId == null) {
            return null;
        }

        return tripId + ":" + Objects.toString(startDate, "");
    }
}
