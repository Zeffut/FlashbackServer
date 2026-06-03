package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.util.*;

public final class ChunkReader {
    public record Result(byte[] snapshot, List<ReplayAction> actions) {}

    private ChunkReader() {}

    public static Result read(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));

        int magic = in.readInt();
        if (magic != ChunkWriter.MAGIC) throw new IOException("Bad magic: " + Integer.toHexString(magic));

        int count = VarCodec.readVarInt(in);
        Map<Integer, String> registry = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int index = VarCodec.readVarInt(in);
            registry.put(index, VarCodec.readString(in));
        }

        int snapshotSize = in.readInt();
        if (snapshotSize < 0) throw new IOException("Negative snapshot size: " + snapshotSize);
        byte[] snapshot = new byte[snapshotSize];
        in.readFully(snapshot);

        List<ReplayAction> actions = new ArrayList<>();
        // Safe: backed by a ByteArrayInputStream, so available() is the exact remaining
        // byte count. Revisit if read() is ever changed to accept a generic InputStream
        // or compressed input, where available() may under-report.
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
        return new Result(snapshot, actions);
    }
}
