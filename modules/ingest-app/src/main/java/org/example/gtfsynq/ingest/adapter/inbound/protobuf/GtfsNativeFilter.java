package org.example.gtfsynq.ingest.adapter.inbound.protobuf;

import com.google.protobuf.CodedInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;
import org.apache.kafka.shaded.com.google.protobuf.WireFormat;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapHashStore;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapLongTable;
import org.example.gtfsynq.shared.util.SizeFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
/**
 * A filter that parses native GTFS feeds from input streams
 */
public class GtfsNativeFilter {

    private final OffHeapHashStore stateStore;

    private final LongHashFunction hashFunction = LongHashFunction.xx3();
    private int lastUpdateCount = 10_000;

    @Value("${gtfs.readBufferSize:8}")
    private int readBufferSizeMb;

    /**
     * Check if the feed header has changed and update the state store if it has
     *
     * @param feedId  The feed ID to use for entity IDs
     * @param feedUrl The feed URL to use for entity IDs
     * @param buffer  The buffer containing the feed header
     *
     * @return true if the header has changed, false otherwise
     *
     * @throws IOException If an error occurs while reading the buffer
     */
    private boolean checkHeaderChanged(String feedId, String feedUrl, byte[] buffer) {
        var headerKey = hashFunction.hashChars(feedId + feedUrl);
        var headerHash = hashFunction.hashBytes(buffer);

        var existingHeaderHash = stateStore.get(headerKey);

        if (headerHash == existingHeaderHash) {
            return false;
        }

        stateStore.put(headerKey, headerHash);
        return true;
    }

    /**
     * Process a single feed entity and return a
     * {@code BinaryFeedEntityWithMetadata} with the entity's bytes and type
     *
     * @param entityBytes       The entity's bytes
     * @param feedIdChars       The feed ID as a char array
     * @param feedIdWithPadding The feed ID with padding as a char array
     *
     * @return A {@code BinaryFeedEntityWithMetadata} with the entity's bytes and
     *         hash
     *
     * @throws IOException If an error occurs while reading the entity
     */
    private BinaryFeedEntityWithMetadata processFeedEntity(byte[] entityBytes, String feedId, Instant feedTs)
            throws IOException {
        var entityCis = CodedInputStream.newInstance(entityBytes);
        var scanResult = GtfsScanner.scanEntity(entityCis);

        var entityId = feedId + ":" + scanResult.id();

        var hashedId = hashFunction.hashBytes(entityId.getBytes());
        var hashedBytes = hashFunction.hashBytes(entityBytes);

        var existingHash = stateStore.get(hashedId);

        if (existingHash == OffHeapLongTable.EMPTY_VALUE) {
            stateStore.put(hashedId, hashedBytes);
            return new BinaryFeedEntityWithMetadata(entityBytes, scanResult.type(), feedTs.getEpochSecond());
        }

        if (hashedBytes == existingHash) {
            return null;
        }

        stateStore.put(hashedId, hashedBytes);

        return new BinaryFeedEntityWithMetadata(entityBytes, scanResult.type(), feedTs.getEpochSecond());
    }

    /**
     * Parse a native GTFS feed from an input stream
     *
     * @param feedId  The feed ID to use for entity IDs
     * @param feedUrl The feed URL to use for entity IDs
     * @param is      The input stream to read from
     * @return A list of parsed entities
     *
     * @throws IOException If an error occurs while reading the stream
     */
    public List<BinaryFeedEntityWithMetadata> parseNative(String feedId, String feedUrl, InputStream is)
            throws IOException {
        var bufferSize = readBufferSizeMb * 1024 * 1024;
        var cis = CodedInputStream.newInstance(new BufferedInputStream(is, bufferSize));

        var changedEntities = new ArrayList<BinaryFeedEntityWithMetadata>(lastUpdateCount);

        var numEntities = 0;
        var numChanged = 0;

        var currentTs = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        while (!cis.isAtEnd()) {
            var tag = cis.readTag();
            var fieldNumber = WireFormat.getTagFieldNumber(tag);

            if (fieldNumber == 0) {
                break;
            } else if (fieldNumber == 1) {
                var headerBytes = cis.readByteArray();

                if (!checkHeaderChanged(feedId, feedUrl, headerBytes)) {
                    log.info("Header hash match for feed {}[{}]", feedId, feedUrl);

                    return changedEntities;
                }
            } else if (fieldNumber == 2) {
                var entityBytes = cis.readByteArray();
                var typedEntity = processFeedEntity(entityBytes, feedId, currentTs);
                numEntities++;

                if (typedEntity != null) {
                    changedEntities.add(typedEntity);
                    numChanged++;
                }
            }
        }

        lastUpdateCount = changedEntities.size();

        log.info(
                "Finished parsing feed {}[{}] {} changed and {} entities total",
                feedId,
                feedUrl,
                SizeFormat.formatNumber(numChanged),
                SizeFormat.formatNumber(numEntities));

        return changedEntities;
    }
}
