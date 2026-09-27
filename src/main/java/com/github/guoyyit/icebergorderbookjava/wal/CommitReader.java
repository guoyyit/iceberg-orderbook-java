package com.github.guoyyit.icebergorderbookjava.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Reads valid frames from a per-symbol log; truncates any torn/corrupt tail. */
public final class CommitReader {

    private CommitReader() {
    }

    public static List<Commit> readAll(String dir, String symbol) throws IOException {
        Path path = Path.of(dir, WalFiles.logFile(symbol));
        List<Commit> commits = new ArrayList<>();
        if (!Files.exists(path)) {
            return commits;
        }

        long validLen;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            long pos = 0;
            long size = ch.size();
            while (pos < size) {
                lenBuf.clear();
                if (readFully(ch, pos, lenBuf) < 4) {
                    break;
                }
                int payloadLen = lenBuf.getInt(0);                if (payloadLen <= 0
                        || payloadLen > 256 * 1024 * 1024
                        || pos + WalFiles.HEADER_BYTES + payloadLen > size) {
                    break; // truncated/invalid tail
                }
                ByteBuffer frame = ByteBuffer.allocate(
                        WalFiles.HEADER_BYTES + payloadLen);
                readFully(ch, pos, frame);
                frame.flip();
                int storedLen = frame.getInt();
                int storedCrc = frame.getInt();
                byte[] payload = new byte[payloadLen];
                frame.get(payload);
                CRC32 crc = new CRC32();
                crc.update(payload);
                if (storedLen != payloadLen || storedCrc != (int) crc.getValue()) {
                    break; // corrupt frame; everything after is untrusted
                }
                try {
                    commits.add(CommitCodec.decode(payload));
                } catch (Exception e) {
                    break;
                }
                pos += WalFiles.HEADER_BYTES + payloadLen;
            }
            validLen = pos;

            if (validLen < size) {
                // drop torn tail so a later append cannot interleave garbage
                try (var wc = FileChannel.open(path,
                        StandardOpenOption.WRITE)) {
                    wc.truncate(validLen);
                    wc.force(true);
                }
            }
        }
        return commits;
    }

    private static int readFully(FileChannel ch, long pos, ByteBuffer dst)
            throws IOException {
        int total = 0;
        while (dst.hasRemaining()) {
            int n = ch.read(dst, pos + total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }
}
