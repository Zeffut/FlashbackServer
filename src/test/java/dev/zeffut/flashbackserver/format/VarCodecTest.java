package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class VarCodecTest {
    @Test
    void varIntRoundTrips() throws IOException {
        for (int value : new int[]{0, 1, 127, 128, 255, 300, 2097151, Integer.MAX_VALUE}) {
            var out = new ByteArrayOutputStream();
            VarCodec.writeVarInt(new DataOutputStream(out), value);
            var in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
            assertEquals(value, VarCodec.readVarInt(in), "varint " + value);
        }
    }

    @Test
    void stringRoundTrips() throws IOException {
        var out = new ByteArrayOutputStream();
        VarCodec.writeString(new DataOutputStream(out), "flashback:action/next_tick");
        var in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("flashback:action/next_tick", VarCodec.readString(in));
    }
}
