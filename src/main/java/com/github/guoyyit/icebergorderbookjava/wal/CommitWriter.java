package com.github.guoyyit.icebergorderbookjava.wal;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Append-only per-symbol durable log. Each append serializes one commit
 * with length + CRC framing and fsyncs before returning: that fsync is
 * the single durability point. Either the whole frame is durable or
 * none of it is visible to replay (a torn tail frame is truncated).
 *
 * <p>Not thread-safe by contract; each symbol's engine is single
 * threaded.
 */
public class CommitWriter implements AutoCloseable {

    private final Path path;
    private OutputStream out;

    public CommitWriter(String dir, String symbol) {
        this.path = Path.of(dir, WalFiles.logFile(symbol));
    }

    public void open() throws IOException {
        Files.createDirectories(path.getParent());
        this.out = new BufferedOutputStream(Files.newOutputStream(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND));
    }

    /** Append and fsync one atomic commit. */
    public void append(Commit commit) throws IOException {
        try {
            byte[] payload = CommitCodec.encode(commit);
            byte[] header = new byte[WalFiles.HEADER_BYTES];
            CRC32 crc = new CRC32();
            crc.update(payload);
            writeInt(header, 0, payload.length);
            writeInt(header, 4, (int) crc.getValue());
            synchronized (this) {
                out.write(header);
                out.write(payload);
                out.flush();
            }
            fsync(path);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("failed to encode commit", e);
        }
    }

    private static void fsync(Path p) throws IOException {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }
}
