package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlashbackValidatorTest {
    private Path validReplay(Path dir) throws Exception {
        Path file = dir.resolve("valid.flashback");
        var meta = new FlashbackMeta();
        meta.name = "valid";
        meta.totalTicks = 2;
        meta.chunks.put("c0.flashback", new ChunkMeta(2));
        byte[] chunk = ChunkWriter.write(new byte[]{1}, List.of(
            new ReplayAction("flashback:action/next_tick", new byte[0]),
            new ReplayAction("flashback:action/next_tick", new byte[0])));
        try (var w = FlashbackContainer.create(file)) {
            w.writeMetadata(meta);
            w.writeChunk("c0.flashback", chunk);
        }
        return file;
    }

    @Test
    void acceptsValidReplay(@TempDir Path dir) throws Exception {
        FlashbackValidator.Report report = FlashbackValidator.validate(validReplay(dir));
        assertTrue(report.valid(), report.problems().toString());
        assertEquals(2, report.totalTicks());
        assertEquals(1, report.chunkCount());
    }

    @Test
    void rejectsMissingMetadataChunk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.flashback");
        var meta = new FlashbackMeta();
        meta.chunks.put("c0.flashback", new ChunkMeta(1)); // declared but not written
        try (var w = FlashbackContainer.create(file)) { w.writeMetadata(meta); }

        FlashbackValidator.Report report = FlashbackValidator.validate(file);
        assertFalse(report.valid());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("c0.flashback")));
    }

    @Test
    void rejectsTickMismatch(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mismatch.flashback");
        var meta = new FlashbackMeta();
        meta.totalTicks = 5;
        meta.chunks.put("c0.flashback", new ChunkMeta(5)); // claims 5 ticks
        byte[] chunk = ChunkWriter.write(new byte[]{1}, List.of(    // but only 1 next_tick present
            new ReplayAction("flashback:action/next_tick", new byte[0])));
        try (var w = FlashbackContainer.create(file)) {
            w.writeMetadata(meta);
            w.writeChunk("c0.flashback", chunk);
        }

        FlashbackValidator.Report report = FlashbackValidator.validate(file);
        assertFalse(report.valid());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("tick mismatch")),
            report.problems().toString());
    }

    @Test
    void rejectsUnreadableContainer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("garbage.flashback");
        Files.write(file, new byte[]{0, 1, 2, 3, 4, 5}); // not a ZIP

        FlashbackValidator.Report report = FlashbackValidator.validate(file);
        assertFalse(report.valid());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("container unreadable")),
            report.problems().toString());
    }

    @Test
    void reportProblemsIsImmutable(@TempDir Path dir) throws Exception {
        FlashbackValidator.Report report = FlashbackValidator.validate(validReplay(dir));
        assertThrows(UnsupportedOperationException.class, () -> report.problems().add("tampered"));
    }
}
