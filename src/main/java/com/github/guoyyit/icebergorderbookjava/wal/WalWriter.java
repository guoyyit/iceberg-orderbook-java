package com.github.guoyyit.icebergorderbookjava.wal;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Append-only framed writer for one symbol's WAL file.
 *
 * <p>Frame layout (little-endian):
 * <pre>
 *   [4 bytes payload length n][4 bytes CRC32 of payload][n bytes UTF-8 JSON]
 * </pre>
 * A batch of events is written and flushed/fsynced together, so recovery
 * never observes a partially committed request: either the whole batch is
 * present or the tail is ignored as a truncated record.
 */
public class WalWriter implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutputStream out;
    private final Path path;
    private final boolean fsync;

    public WalWriter(String path, boolean fsync) throws IOException {
        this.path = Path.of(path);
        this.fsync = fsync;
        Files.createDirectories(this.path.getParent());
        this.out = new BufferedOutputStream(Files.newOutputStream(this.path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
    }

    public synchronized void appendBatch(List<WalEvent> events) throws IOException {
        for (WalEvent e : events) {
            byte[] payload = MAPPER.writeValueAsBytes(e);
            CRC32 crc = new CRC32();
            crc.update(payload);
            writeInt(payload.length);
            writeInt((int) crc.getValue());
            out.write(payload);
        }
        out.flush();
        if (fsync) {
            force();
        }
    }

    private void force() throws IOException {
        // Re-open a channel against the same file just for fsync; the stream
        // keeps appending through its own handle afterwards.
        try (var ch = Files.newByteChannel(path, StandardOpenOption.WRITE)) {
            ((java.nio.channels.FileChannel) ch).force(true);
        }
    }

    private void writeInt(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    @Override
    public synchronized void close() throws IOException {
        out.flush();
        if (fsync) {
            force();
        }
        out.close();
    }
}
