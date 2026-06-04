package dev.zeffut.flashbackserver.record;
import dev.zeffut.flashbackserver.format.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FlashbackRecorderRolloverTest {
    @Test
    void rollsChunksAtThreshold(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("roll.flashback");
        FlashbackRecorder r = FlashbackRecorder.withChunkLength(out, "P", 770, 4325, 3);
        r.setSnapshot(List.of(new ReplayAction("flashback:action/create_local_player", new byte[]{1})));
        for (int i = 0; i < 7; i++) { r.onPacket(new byte[]{(byte) i}); r.onTick(); }
        r.stop();
        FlashbackValidator.Report report = FlashbackValidator.validate(out);
        assertTrue(report.valid(), report.problems().toString());
        assertEquals(7, report.totalTicks());
        assertTrue(report.chunkCount() >= 3, "expected >=3 chunks, got " + report.chunkCount());
    }

    @Test
    void rollChunkForcesBoundary(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("roll2.flashback");
        FlashbackRecorder r = FlashbackRecorder.withChunkLength(out, "P", 770, 4325, 9999);
        r.setSnapshot(List.of(new ReplayAction("flashback:action/create_local_player", new byte[]{1})));
        r.onPacket(new byte[]{1}); r.onTick();
        r.rollChunk();
        r.onPacket(new byte[]{2}); r.onTick();
        r.stop();
        FlashbackValidator.Report report = FlashbackValidator.validate(out);
        assertTrue(report.valid(), report.problems().toString());
        assertEquals(2, report.totalTicks());
        assertEquals(2, report.chunkCount());
    }
}
