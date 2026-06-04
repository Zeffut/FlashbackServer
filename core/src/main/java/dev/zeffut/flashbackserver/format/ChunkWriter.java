package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.util.*;

public final class ChunkWriter {
    // Confirmed in docs/format/flashback-format.md from Flashback.java — the real MAGIC
    // so files are interop-compatible with the Flashback client.
    static final int MAGIC = 0xD780_E884;

    private ChunkWriter() {}

    /**
     * Writes a chunk where {@code snapshotActions} and {@code streamActions} share ONE registry.
     * The registry is built in first-appearance order across snapshot then stream, ensuring snapshot
     * action-ids resolve correctly against the same registry used by the stream.
     */
    public static byte[] write(List<ReplayAction> snapshotActions, List<ReplayAction> streamActions) throws IOException {
        // 1. Build ONE registry spanning snapshot then stream (first-appearance order).
        LinkedHashMap<String, Integer> registry = new LinkedHashMap<>();
        for (ReplayAction a : snapshotActions) registry.computeIfAbsent(a.identifier(), k -> registry.size());
        for (ReplayAction a : streamActions)   registry.computeIfAbsent(a.identifier(), k -> registry.size());

        var out = new ByteArrayOutputStream();
        var dos = new DataOutputStream(out);

        // 2. Magic + registry
        dos.writeInt(MAGIC);
        // Registry: count, then the identifiers in index order. The zero-based POSITION in this
        // list is the numeric id — no explicit per-entry index is written (matches Flashback's
        // writeIdentifier loop). registry is a LinkedHashMap, so keySet() is in insertion/index order.
        VarCodec.writeVarInt(dos, registry.size());
        for (String identifier : registry.keySet()) {
            VarCodec.writeString(dos, identifier);
        }

        // 3. Snapshot block: int32 size + encoded snapshot actions
        byte[] snapshotBytes = encodeActions(registry, snapshotActions);
        dos.writeInt(snapshotBytes.length);
        dos.write(snapshotBytes);

        // 4. Stream: encoded stream actions written directly after snapshot block
        byte[] streamBytes = encodeActions(registry, streamActions);
        dos.write(streamBytes);

        dos.flush();
        return out.toByteArray();
    }

    /**
     * Encodes a list of actions into bytes using the provided registry.
     * Each record: varint registryId + int32 payloadLen + payload.
     */
    private static byte[] encodeActions(Map<String, Integer> registry, List<ReplayAction> actions) throws IOException {
        var buf = new ByteArrayOutputStream();
        var dos = new DataOutputStream(buf);
        for (ReplayAction a : actions) {
            VarCodec.writeVarInt(dos, registry.get(a.identifier()));
            dos.writeInt(a.payload().length);
            dos.write(a.payload());
        }
        dos.flush();
        return buf.toByteArray();
    }
}
