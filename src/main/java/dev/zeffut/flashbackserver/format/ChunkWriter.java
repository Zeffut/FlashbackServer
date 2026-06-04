package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.util.*;

public final class ChunkWriter {
    // Confirmed in docs/format/flashback-format.md from Flashback.java — the real MAGIC
    // so files are interop-compatible with the Flashback client.
    static final int MAGIC = 0xD780_E884;

    private ChunkWriter() {}

    public static byte[] write(byte[] snapshot, List<ReplayAction> actions) throws IOException {
        LinkedHashMap<String, Integer> registry = new LinkedHashMap<>();
        for (ReplayAction a : actions) registry.computeIfAbsent(a.identifier(), k -> registry.size());

        var out = new ByteArrayOutputStream();
        var dos = new DataOutputStream(out);

        dos.writeInt(MAGIC);

        // Registry: count, then the identifiers in index order. The zero-based POSITION in this
        // list is the numeric id — no explicit per-entry index is written (matches Flashback's
        // writeIdentifier loop). registry is a LinkedHashMap, so entrySet() is in insertion/index order.
        VarCodec.writeVarInt(dos, registry.size());
        for (String identifier : registry.keySet()) {
            VarCodec.writeString(dos, identifier);
        }

        dos.writeInt(snapshot.length);
        dos.write(snapshot);

        for (ReplayAction a : actions) {
            VarCodec.writeVarInt(dos, registry.get(a.identifier()));
            dos.writeInt(a.payload().length);
            dos.write(a.payload());
        }
        dos.flush();
        return out.toByteArray();
    }
}
