package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class VarCodec {
    private VarCodec() {}

    /**
     * Writes an unsigned LEB128 VarInt. Negative values are encoded as 5 bytes
     * (via the unsigned shift) and round-trip correctly through {@link #readVarInt}.
     * Do not change {@code >>>} to {@code >>} — that would break the negative-value contract.
     */
    public static void writeVarInt(DataOutput out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int readVarInt(DataInput in) throws IOException {
        int result = 0, shift = 0, b;
        do {
            b = in.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return result;
    }

    public static void writeString(DataOutput out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInput in) throws IOException {
        int len = readVarInt(in);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
