package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that asserts a recording produced by a real Paper 1.21.5 server contains the
 * renderable initial-state snapshot: login + create_local_player + position + chunk packets in the
 * first chunk's snapshot section.
 *
 * <p>This test complements {@link RecordingIT} (structural oracle-validity) with the
 * snapshot-content check from {@link FlashbackValidator#validateRenderable(Path)}.
 *
 * <p>Decode-replay smoke (feeding snapshot payloads through MCProtocolLib in isolation) was deemed
 * impractical: MCProtocolLib decoding requires live session / protocol-state context and cannot
 * decode raw PLAY packet bytes without a connected session. The structural {@code validateRenderable}
 * check + the human P6 spot-check are the gates for R3a.
 */
@Tag("integration")
class RenderableRecordingIT {

    @Test
    void recordedFilePassesValidateRenderable(@TempDir Path dir) throws Exception {
        int port = 25614;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "RenderBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join within timeout");
                server.sendCommand("replay start players RenderBot");
                assertTrue(server.awaitLogLine("Recording RenderBot", 15), "start not confirmed");
                Thread.sleep(4000); // accrue ticks + packets so the snapshot is flushed
                server.sendCommand("replay stop players RenderBot");
                assertTrue(server.awaitLogLine("Saved replay:", 15), "stop not confirmed");
            }

            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            try (Stream<Path> files = Files.list(replays)) {
                Path replay = files
                    .filter(p -> p.toString().endsWith(".flashback"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no .flashback produced in " + replays));

                FlashbackValidator.Report report = FlashbackValidator.validateRenderable(replay);
                assertTrue(
                    report.valid(),
                    "validateRenderable failed — snapshot is missing required elements: "
                        + report.problems()
                );
            }
        }
    }
}
