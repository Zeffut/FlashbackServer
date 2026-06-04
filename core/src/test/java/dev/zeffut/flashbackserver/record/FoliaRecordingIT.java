package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class FoliaRecordingIT {
    @Test
    void recordsAnOracleValidReplayOnFolia(@TempDir Path dir) throws Exception {
        int port = 25605;
        try (PaperTestServer server = PaperTestServer.start(dir, port, "folia")) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "FoliaBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join the Folia server");
                server.sendCommand("replay start players FoliaBot");
                assertTrue(server.awaitLogLine("Recording FoliaBot", 15), "start not confirmed");
                Thread.sleep(4000);
                server.sendCommand("replay stop players FoliaBot");
                assertTrue(server.awaitLogLine("Saved replay:", 20), "replay not written");
            }
            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            try (Stream<Path> files = Files.list(replays)) {
                Path replay = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no .flashback produced on Folia in " + replays));
                FlashbackValidator.Report report = FlashbackValidator.validate(replay);
                assertTrue(report.valid(), report.problems().toString());
                assertTrue(report.totalTicks() > 0, "recording had zero ticks on Folia");
            }
        }
    }
}
