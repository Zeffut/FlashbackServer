package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.regex.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: proves a saved clip is renderable — its packets decode cleanly (errors == 0,
 * decoded >= 25) AND it passes {@link FlashbackValidator#validateRenderable(Path)}.
 *
 * <p>The clip snapshot must contain: configuration_packet, create_local_player, login (id 43),
 * position (id 65), level-chunk (id 39), and player-info (id 63).
 */
@Tag("integration")
class RenderableClipIT {

    private static int[] parseVerify(String line) {  // returns {decoded, errors}
        Matcher m = Pattern.compile("decoded=(\\d+) errors=(\\d+)").matcher(line);
        assertTrue(m.find(), "could not parse verify line: " + line);
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    @Test
    void clipIsRenderable(@TempDir Path dir) throws Exception {
        int port = 25616;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "ClipRenderBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join within timeout");
                server.sendCommand("replay clip arm ClipRenderBot");
                assertTrue(server.awaitLogLine("Armed clips for ClipRenderBot", 15), "arm not confirmed");
                // Allow the first keyframe (at arm) + several seconds of frames to accumulate
                Thread.sleep(6000);
                server.sendCommand("replay clip save ClipRenderBot");
                assertTrue(server.awaitLogLine("Saved clip:", 20), "clip not written");
            }

            Path clips = server.runDir().resolve("plugins/FlashbackServer/clips");
            Path clipFile;
            try (Stream<Path> files = Files.list(clips)) {
                clipFile = files
                    .filter(p -> p.toString().endsWith(".flashback"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no clip produced in " + clips));
            }

            String fileName = clipFile.getFileName().toString();

            // Verify decode: errors == 0, decoded >= 25
            server.sendCommand("replay verify " + fileName);
            String verifyLine = server.awaitLogLineContaining("Verify " + fileName + ":", 30);
            assertNotNull(verifyLine, "verify did not produce output for clip");
            int[] r = parseVerify(verifyLine);
            assertEquals(0, r[1], "decode errors in clip: " + verifyLine
                + " (see [paper] log for Verify problem: lines)");
            assertTrue(r[0] >= 25, "decoded too few packets (" + r[0] + " < 25): " + verifyLine
                + " — snapshot keyframe may be missing; check ClipManager prepends cachedConfig");

            // Structural + snapshot-content check
            FlashbackValidator.Report report = FlashbackValidator.validateRenderable(clipFile);
            assertTrue(report.valid(),
                "validateRenderable failed — snapshot is missing required elements: "
                    + report.problems()
                    + " (decoded=" + r[0] + " errors=" + r[1] + ")");
        }
    }
}
