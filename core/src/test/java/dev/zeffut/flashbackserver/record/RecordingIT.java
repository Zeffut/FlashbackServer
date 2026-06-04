package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class RecordingIT {
    @Test
    void recordsAnOracleValidReplay(@TempDir Path dir) throws Exception {
        int port = 25604;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "CaptureBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                server.sendCommand("replay start players CaptureBot");
                assertTrue(server.awaitLogLine("Recording CaptureBot", 15), "start not confirmed");
                Thread.sleep(4000); // accrue ticks + packets
                server.sendCommand("replay stop players CaptureBot");
                assertTrue(server.awaitLogLine("Saved replay:", 15), "stop not confirmed");
            }
            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            try (Stream<Path> files = Files.list(replays)) {
                Path replay = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no .flashback produced in " + replays));
                FlashbackValidator.Report report = FlashbackValidator.validate(replay);
                assertTrue(report.valid(), report.problems().toString());
                assertTrue(report.totalTicks() > 0, "recording had zero ticks");
            }
        }
    }
}
