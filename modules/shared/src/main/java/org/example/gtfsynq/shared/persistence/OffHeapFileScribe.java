package org.example.gtfsynq.shared.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapLongTable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Saves and loads {@link OffHeapLongTable} state to/from a file.
 *
 * <p>
 * The dump format is a 16-byte header (magic + capacity in slots) followed by
 * the raw off-heap memory, so a resized table can be restored correctly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OffHeapFileScribe {

    private static final long MAGIC = 0x47544653594E5101L;
    private static final int HEADER_SIZE = Long.BYTES * 2;

    /**
     * Transfer chunk size. {@code MemorySegment.asByteBuffer()} is limited to
     * {@link Integer#MAX_VALUE} bytes, which the table can exceed at max
     * capacity (2^26 slots x 32 bytes = 2^31 bytes), so large segments are
     * transferred in slices.
     */
    private static final long CHUNK_BYTES = 64L * 1024 * 1024;

    /**
     * The path to the file where state is saved/loaded.
     */
    private final Path savePath;

    @Autowired
    public OffHeapFileScribe(@Value("${state.save.path:state_dump.bin}") String path) {
        this.savePath = Path.of(path);
        log.info("State Scribe initialized with path: {}", this.savePath.toAbsolutePath());
    }

    /**
     * Dumps the state of the given {@link OffHeapLongTable} to the save path
     *
     * @param table the table to dump
     */
    public void dump(OffHeapLongTable table) {
        var start = System.currentTimeMillis();

        // Snapshot segment and capacity together: capacity is derived from the
        // captured segment so the header always matches the dumped bytes even
        // if a resize happens mid-dump.
        var segment = table.getSegment();
        var capacity = segment.byteSize() / OffHeapLongTable.SLOT_SIZE;

        var tmpPath = savePath.resolveSibling(savePath.getFileName().toString() + ".tmp");

        try {
            var parent = tmpPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            log.error("Failed to create state directory", e);
            return;
        }

        try (var channel = FileChannel.open(
                tmpPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            var header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            header.putLong(MAGIC).putLong(capacity).flip();
            writeFully(channel, header);

            // Write the off-heap memory segment to the file, chunked so the
            // segment can exceed the 2 GiB ByteBuffer limit
            for (var offset = 0L; offset < segment.byteSize(); offset += CHUNK_BYTES) {
                var chunk = Math.min(CHUNK_BYTES, segment.byteSize() - offset);
                writeFully(channel, segment.asSlice(offset, chunk).asByteBuffer());
            }
        } catch (IOException e) {
            log.error("Failed to dump state", e);
            return;
        }

        try {
            try {
                Files.move(tmpPath, savePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmpPath, savePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to move dumped state into place", e);
            return;
        }

        log.info("State of {} slots dumped to {} in {}ms", capacity, savePath, System.currentTimeMillis() - start);
    }

    /**
     * Loads the state of the given {@link OffHeapLongTable} from the save path
     *
     * @param table the table to load
     */
    public void load(OffHeapLongTable table) {
        if (!Files.exists(savePath)) return;

        var start = System.currentTimeMillis();
        try (var channel = FileChannel.open(savePath, StandardOpenOption.READ)) {
            var header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            if (!readFully(channel, header)) {
                log.warn("State file {} is empty, skipping load", savePath);
                return;
            }
            header.flip();

            var magic = header.getLong();
            var capacity = header.getLong();
            if (magic != MAGIC
                    || capacity < 1
                    || Long.bitCount(capacity) != 1
                    || capacity > OffHeapLongTable.MAX_CAPACITY) {
                log.warn("State file {} has an invalid or legacy header, skipping load", savePath);
                return;
            }

            if (capacity != table.capacity()) {
                table.reallocate(capacity);
            }

            // Read the file directly back into the off-heap memory segment,
            // chunked so the segment can exceed the 2 GiB ByteBuffer limit
            var segment = table.getSegment();
            for (var offset = 0L; offset < segment.byteSize(); offset += CHUNK_BYTES) {
                var chunk = Math.min(CHUNK_BYTES, segment.byteSize() - offset);
                var buffer = segment.asSlice(offset, chunk).asByteBuffer();
                if (!readFully(channel, buffer)) {
                    log.warn("State file {} is truncated, some slots left empty", savePath);
                    return;
                }
            }

            log.info(
                    "State of {} slots loaded from {} in {}ms", capacity, savePath, System.currentTimeMillis() - start);
        } catch (IOException e) {
            log.error("Failed to load state", e);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written < 0) {
                throw new IOException("Unexpected end of channel while writing state file");
            }
        }
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) == -1) {
                return false;
            }
        }
        return true;
    }
}
