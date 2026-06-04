package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChunkCodecTest {

    /** Basic round-trip with an empty snapshot. */
    @Test
    void chunkRoundTripsEmptySnapshot() throws Exception {
        var streamActions = List.of(
            new ReplayAction("flashback:game_packet", new byte[]{10, 20}),
            new ReplayAction("flashback:action/next_tick", new byte[0]),
            new ReplayAction("flashback:game_packet", new byte[]{30})
        );

        byte[] bytes = ChunkWriter.write(List.of(), streamActions);
        ChunkReader.Result result = ChunkReader.read(bytes);

        assertTrue(result.snapshotActions().isEmpty());
        assertEquals(streamActions.size(), result.streamActions().size());
        assertEquals("flashback:action/next_tick", result.streamActions().get(1).identifier());
        assertArrayEquals(new byte[]{30}, result.streamActions().get(2).payload());
    }

    /**
     * Round-trip with a non-empty snapshot. Snapshot and stream share ONE registry,
     * so snapshot action-ids resolve correctly on read.
     */
    @Test
    void chunkRoundTripsNonEmptySnapshot() throws Exception {
        var snapshotActions = List.of(
            new ReplayAction("flashback:action/game_packet", new byte[]{1, 2}),
            new ReplayAction("flashback:action/create_local_player", new byte[]{3, 4, 5})
        );
        var streamActions = List.of(
            new ReplayAction("flashback:action/game_packet", new byte[]{10, 20}),
            new ReplayAction("flashback:action/next_tick", new byte[0]),
            new ReplayAction("flashback:action/game_packet", new byte[]{30})
        );

        byte[] bytes = ChunkWriter.write(snapshotActions, streamActions);
        ChunkReader.Result result = ChunkReader.read(bytes);

        // Snapshot actions round-trip correctly
        assertEquals(2, result.snapshotActions().size());
        assertEquals("flashback:action/game_packet", result.snapshotActions().get(0).identifier());
        assertArrayEquals(new byte[]{1, 2}, result.snapshotActions().get(0).payload());
        assertEquals("flashback:action/create_local_player", result.snapshotActions().get(1).identifier());
        assertArrayEquals(new byte[]{3, 4, 5}, result.snapshotActions().get(1).payload());

        // Stream actions round-trip correctly
        assertEquals(3, result.streamActions().size());
        assertEquals("flashback:action/next_tick", result.streamActions().get(1).identifier());
        assertArrayEquals(new byte[]{30}, result.streamActions().get(2).payload());
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
