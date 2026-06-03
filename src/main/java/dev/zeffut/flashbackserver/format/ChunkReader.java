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
        byte[] snapshot = new byte[snapshotSize];
        in.readFully(snapshot);

        List<ReplayAction> actions = new ArrayList<>();
        while (in.available() > 0) {
            int id = VarCodec.readVarInt(in);
            int size = in.readInt();
            byte[] payload = new byte[size];
            in.readFully(payload);
            String identifier = registry.get(id);
            if (identifier == null) throw new IOException("Unknown action id: " + id);
            actions.add(new ReplayAction(identifier, payload));
        }
        return new Result(snapshot, actions);
    }
}
