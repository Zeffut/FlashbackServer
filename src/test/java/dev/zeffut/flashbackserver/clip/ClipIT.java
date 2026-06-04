package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ClipIT {
    @Test
    void savesAnOracleValidClip(@TempDir Path dir) throws Exception {
        int port = 25606;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "ClipBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                server.sendCommand("replay clip arm ClipBot");
                assertTrue(server.awaitLogLine("Armed clips for ClipBot", 15), "arm not confirmed");
                Thread.sleep(4000); // accrue packets + ticks into the rolling buffer
                server.sendCommand("replay clip save ClipBot");
                assertTrue(server.awaitLogLine("Saved clip:", 20), "clip not written");
            }
            Path clips = server.runDir().resolve("plugins/FlashbackServer/clips");
            try (Stream<Path> files = Files.list(clips)) {
                Path clip = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no clip produced in " + clips));
                FlashbackValidator.Report report = FlashbackValidator.validate(clip);
                assertTrue(report.valid(), report.problems().toString());
                assertTrue(report.totalTicks() > 0, "clip had zero ticks");
                assertTrue(report.totalTicks() <= 600, "clip exceeded the 30s window: " + report.totalTicks());
            }
        }
    }
}
