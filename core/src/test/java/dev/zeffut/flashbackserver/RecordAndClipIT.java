package dev.zeffut.flashbackserver;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.BotClient;
import dev.zeffut.flashbackserver.harness.PaperTestServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that recording and clipping can coexist for the same player without the raw capture
 * handler collision (Defect 1). Both a replay file and a clip file must be produced and
 * oracle-valid.
 */
@Tag("integration")
class RecordAndClipIT {

    @Test
    void recordAndClipCoexistForSamePlayer(@TempDir Path dir) throws Exception {
        int port = 25607;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "Bot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");

                // Arm BOTH features simultaneously
                server.sendCommand("replay start players Bot");
                assertTrue(server.awaitLogLine("Recording Bot", 15), "recording start not confirmed");

                server.sendCommand("replay clip arm Bot");
                assertTrue(server.awaitLogLine("Armed clips for Bot", 15), "clip arm not confirmed");

                // Let some packets and ticks accumulate
                Thread.sleep(4000);

                // Save clip first (keep bot connected while saving)
                server.sendCommand("replay clip save Bot");
                assertTrue(server.awaitLogLine("Saved clip:", 20), "clip not written");

                // Then stop the recording
                server.sendCommand("replay stop players Bot");
                assertTrue(server.awaitLogLine("Saved replay:", 20), "replay not written");
            }

            // Validate replay file
            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            Path replay;
            try (Stream<Path> files = Files.list(replays)) {
                replay = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                        .orElseThrow(() -> new AssertionError("no replay file produced in " + replays));
            }
            FlashbackValidator.Report replayReport = FlashbackValidator.validate(replay);
            assertTrue(replayReport.valid(), "replay invalid: " + replayReport.problems());
            assertTrue(replayReport.totalTicks() > 0, "replay had zero ticks");

            // Validate clip file
            Path clips = server.runDir().resolve("plugins/FlashbackServer/clips");
            Path clip;
            try (Stream<Path> files = Files.list(clips)) {
                clip = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                        .orElseThrow(() -> new AssertionError("no clip file produced in " + clips));
            }
            FlashbackValidator.Report clipReport = FlashbackValidator.validate(clip);
            assertTrue(clipReport.valid(), "clip invalid: " + clipReport.problems());
            assertTrue(clipReport.totalTicks() > 0, "clip had zero ticks");
        }
    }
}
