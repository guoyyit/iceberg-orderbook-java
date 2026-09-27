package com.github.guoyyit.icebergorderbookjava.wal;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared Jackson mapper for commit (de)serialization. */
public final class CommitCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    private CommitCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static byte[] encode(Commit commit) throws Exception {
        return MAPPER.writeValueAsBytes(commit);
    }

    public static Commit decode(byte[] data) throws Exception {
        return MAPPER.readValue(data, Commit.class);
    }
}
