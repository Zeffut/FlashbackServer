package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
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
        assertThrows(Exception.class, () -> ChunkReader.read(garbage));
    }
}
