package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlashbackContainerTest {
    @Test
    void containerRoundTrips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("test.flashback");

        var meta = new FlashbackMeta();
        meta.name = "demo";
        meta.totalTicks = 20;
        meta.chunks.put("c0.flashback", new ChunkMeta(20));

        byte[] chunk = ChunkWriter.write(List.of(),
            List.of(new ReplayAction("flashback:action/next_tick", new byte[0])));

        try (var writer = FlashbackContainer.create(file)) {
            writer.writeMetadata(meta);
            writer.writeChunk("c0.flashback", chunk);
        }

        assertTrue(Files.exists(file));
        try (var reader = FlashbackContainer.open(file)) {
            assertEquals("demo", reader.readMetadata().name);
            assertArrayEquals(chunk, reader.readChunk("c0.flashback"));
            assertTrue(reader.entryNames().contains("metadata.json"));
            assertTrue(reader.entryNames().contains("c0.flashback"));
        }
    }
}
