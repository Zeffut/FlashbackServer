package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FlashbackRecorderTest {
    @Test
    void producesOracleValidFile(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("rec.flashback");
        FlashbackRecorder recorder = new FlashbackRecorder(out, "TestPlayer", 769, 4189);
        recorder.onPacket(new byte[]{1, 2, 3});
        recorder.onTick();
        recorder.onPacket(new byte[]{4});
        recorder.onTick();
        recorder.stop();

        FlashbackValidator.Report report = FlashbackValidator.validate(out);
        assertTrue(report.valid(), report.problems().toString());
        assertEquals(2, report.totalTicks());
        assertEquals(1, report.chunkCount());
    }

    @Test
    void stopIsIdempotent(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("rec2.flashback");
        FlashbackRecorder recorder = new FlashbackRecorder(out, "P", 769, 4189);
        recorder.onTick();
        recorder.stop();
        recorder.stop(); // second stop is a no-op, does not throw
        assertTrue(FlashbackValidator.validate(out).valid());
    }
}
