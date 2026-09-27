package com.github.guoyyit.icebergorderbookjava.wal;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Reads framed WAL records.
 *
 * <p>Recovery stops cleanly at:
 * <ul>
 *   <li>EOF (clean shutdown),</li>
 *   <li>a truncated trailing frame (crash mid-write),</li>
 *   <li>a checksum mismatch (torn write).</li>
 * </ul>
 * Every fully committed record before the bad tail is replayed, so committed
 * trades are never lost while unpersisted intermediate state is never
 * replayed.
 */
public final class WalReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HEADER = 8;
    private static final int MAX_FRAME = 64 * 1024 * 1024;

    private WalReader() {
    }

    public static List<WalEvent> readAll(String file) throws IOException {
        Path path = Path.of(file);
        List<WalEvent> out = new ArrayList<>();
        if (!Files.exists(path) || Files.size(path) == 0) {
            return out;
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer hdr = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
            while (true) {
                hdr.clear();
                int n = readFully(ch, hdr);
                if (n == 0) {
                    break; // clean EOF
                }
                if (n < HEADER) {
                    break; // truncated header: ignore torn tail
                }
                hdr.flip();
                int len = hdr.getInt();
                int expectedCrc = hdr.getInt();
                if (len <= 0 || len > MAX_FRAME) {
                    break; // implausible frame: treat tail as corrupt
                }
                ByteBuffer body = ByteBuffer.allocate(len);
                if (readFully(ch, body) < len) {
                    break; // truncated body
                }
                body.flip();
                byte[] payload = new byte[len];
                body.get(payload);
                CRC32 crc = new CRC32();
                crc.update(payload);
                if ((int) crc.getValue() != expectedCrc) {
                    break; // torn / corrupt tail
                }
                out.add(MAPPER.readValue(payload, WalEvent.class));
            }
        }
        return out;
    }

    private static int readFully(FileChannel ch, ByteBuffer buf) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int n = ch.read(buf);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }
}
