package com.github.guoyyit.icebergorderbookjava.wal;

import java.nio.charset.StandardCharsets;

/** Symbol to safe file-name mapping and constants. */
public final class WalFiles {

    /** Frame: int32 length, int32 CRC32 of payload, payload. */
    public static final int HEADER_BYTES = 8;

    private WalFiles() {
    }

    public static String logFile(String symbol) {
        byte[] b = symbol.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("sym-");
        for (byte x : b) {
            char c = (char) (x & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_').append(Integer.toHexString(x & 0xff));
            }
        }
        sb.append(".log");
        return sb.toString();
    }
}
