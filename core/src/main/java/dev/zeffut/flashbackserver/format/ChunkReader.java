package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.util.*;

public final class ChunkReader {
    public record Result(List<ReplayAction> snapshotActions, List<ReplayAction> streamActions) {}

    private ChunkReader() {}

    public static Result read(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));

        int magic = in.readInt();
        if (magic != ChunkWriter.MAGIC) throw new IOException("Bad magic: " + Integer.toHexString(magic));

        int count = VarCodec.readVarInt(in);
        if (count < 0) throw new IOException("Negative action registry count: " + count);
        Map<Integer, String> registry = new HashMap<>();
        for (int i = 0; i < count; i++) {
            // Position is the id — identifiers only, no explicit index (matches Flashback's format).
            registry.put(i, VarCodec.readString(in));
        }

        int snapshotSize = in.readInt();
        if (snapshotSize < 0) throw new IOException("Negative snapshot size: " + snapshotSize);

        // Parse snapshot actions from a bounded sub-stream of exactly snapshotSize bytes.
        byte[] snapshotBytes = new byte[snapshotSize];
        in.readFully(snapshotBytes);
        List<ReplayAction> snapshotActions = parseActions(registry, snapshotBytes);

        // Parse stream actions from the remainder until EOF.
        // Safe: backed by a ByteArrayInputStream, so available() is the exact remaining
        // byte count. Revisit if read() is ever changed to accept a generic InputStream
        // or compressed input, where available() may under-report.
        List<ReplayAction> streamActions = new ArrayList<>();
        while (in.available() > 0) {
            int id = VarCodec.readVarInt(in);
            int size = in.readInt();
            if (size < 0) throw new IOException("Negative payload size: " + size);
            byte[] payload = new byte[size];
            in.readFully(payload);
            String identifier = registry.get(id);
            if (identifier == null) throw new IOException("Unknown action id: " + id);
            streamActions.add(new ReplayAction(identifier, payload));
        }
        return new Result(snapshotActions, streamActions);
    }

    /**
     * Parses action records from {@code bytes} until they are fully consumed.
     * Format: varint id + int32 size + payload (repeated).
     */
    private static List<ReplayAction> parseActions(Map<Integer, String> registry, byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        List<ReplayAction> actions = new ArrayList<>();
        while (in.available() > 0) {
            int id = VarCodec.readVarInt(in);
            int size = in.readInt();
            if (size < 0) throw new IOException("Negative payload size: " + size);
            byte[] payload = new byte[size];
            in.readFully(payload);
            String identifier = registry.get(id);
            if (identifier == null) throw new IOException("Unknown action id: " + id);
            actions.add(new ReplayAction(identifier, payload));
        }
        return actions;
    }
}
