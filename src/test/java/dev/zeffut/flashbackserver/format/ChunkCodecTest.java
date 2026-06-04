package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChunkCodecTest {
    @Test
    void chunkRoundTrips() throws Exception {
        byte[] snapshot = new byte[]{1, 2, 3, 4};
        var actions = List.of(
            new ReplayAction("flashback:game_packet", new byte[]{10, 20}),
            new ReplayAction("flashback:action/next_tick", new byte[0]),
            new ReplayAction("flashback:game_packet", new byte[]{30})
        );

        byte[] bytes = ChunkWriter.write(snapshot, actions);
        ChunkReader.Result result = ChunkReader.read(bytes);

        assertArrayEquals(snapshot, result.snapshot());
        assertEquals(actions.size(), result.actions().size());
        assertEquals("flashback:action/next_tick", result.actions().get(1).identifier());
        assertArrayEquals(new byte[]{30}, result.actions().get(2).payload());
    }

    @Test
    void rejectsBadMagic() {
        byte[] garbage = new byte[]{0, 0, 0, 0, 0};
        assertThrows(IOException.class, () -> ChunkReader.read(garbage));
    }

    @Test
    void rejectsNegativePayloadSize() throws Exception {
        var out = new ByteArrayOutputStream();
        var dos = new DataOutputStream(out);
        dos.writeInt(ChunkWriter.MAGIC);
        VarCodec.writeVarInt(dos, 1);                 // registry count = 1
        VarCodec.writeString(dos, "flashback:game_packet");  // identifier only; position 0 = id 0
        dos.writeInt(0);                              // empty snapshot
        VarCodec.writeVarInt(dos, 0);                 // action id 0
        dos.writeInt(-1);                             // malicious negative payload size
        dos.flush();

        IOException ex = assertThrows(IOException.class, () -> ChunkReader.read(out.toByteArray()));
        assertTrue(ex.getMessage().contains("Negative payload size"), ex.getMessage());
    }

    @Test
    void rejectsUnknownActionId() throws Exception {
        // Hand-craft a chunk whose action references a registry id that was never declared.
        var out = new ByteArrayOutputStream();
        var dos = new DataOutputStream(out);
        dos.writeInt(ChunkWriter.MAGIC);
        VarCodec.writeVarInt(dos, 1);                 // registry count = 1
        VarCodec.writeString(dos, "flashback:game_packet");  // identifier only; position 0 = id 0
        dos.writeInt(0);                              // empty snapshot
        VarCodec.writeVarInt(dos, 5);                 // action id 5 — not in the registry
        dos.writeInt(0);                              // empty payload
        dos.flush();

        IOException ex = assertThrows(IOException.class, () -> ChunkReader.read(out.toByteArray()));
        assertTrue(ex.getMessage().contains("Unknown action id"), ex.getMessage());
    }
}
