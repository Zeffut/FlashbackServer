package dev.zeffut.flashbackserver.verify;

import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.regex.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class DecodeVerifyIT {
    private static int[] parseVerify(String line) {  // returns {decoded, errors}
        Matcher m = Pattern.compile("decoded=(\\d+) errors=(\\d+)").matcher(line);
        assertTrue(m.find(), "could not parse verify line: " + line);
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    @Test
    void recordedReplayDecodesCleanly(@TempDir Path dir) throws Exception {
        int port = 25608;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            String file;
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "VerifyBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                server.sendCommand("replay start players VerifyBot");
                assertTrue(server.awaitLogLine("Recording VerifyBot", 15), "start not confirmed");
                Thread.sleep(4000);
                server.sendCommand("replay stop players VerifyBot");
                assertTrue(server.awaitLogLine("Saved replay:", 20), "stop not confirmed");
            }
            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            try (Stream<Path> files = Files.list(replays)) {
                file = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no replay produced")).getFileName().toString();
            }
            server.sendCommand("replay verify " + file);
            String line = server.awaitLogLineContaining("Verify " + file + ":", 30);
            assertNotNull(line, "verify did not run");
            int[] r = parseVerify(line);
            assertTrue(r[0] > 0, "decoded zero packets: " + line);
            assertEquals(0, r[1], "decode errors in recording: " + line + " (see [paper] log for problems)");
        }
    }

    @Test
    void clipDecodesCleanly(@TempDir Path dir) throws Exception {
        int port = 25609;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            String file;
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "ClipVerifyBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                server.sendCommand("replay clip arm ClipVerifyBot");
                assertTrue(server.awaitLogLine("Armed clips for ClipVerifyBot", 15), "arm not confirmed");
                Thread.sleep(4000);
                server.sendCommand("replay clip save ClipVerifyBot");
                assertTrue(server.awaitLogLine("Saved clip:", 20), "clip not written");
            }
            Path clips = server.runDir().resolve("plugins/FlashbackServer/clips");
            try (Stream<Path> files = Files.list(clips)) {
                file = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no clip produced")).getFileName().toString();
            }
            server.sendCommand("replay verify " + file);
            String line = server.awaitLogLineContaining("Verify " + file + ":", 30);
            assertNotNull(line, "verify did not run for clip");
            int[] r = parseVerify(line);
            // Clips have an empty snapshot (pure captured stream); decoded may be 0 if no stream packets
            // were emitted during the short window, but errors must always be 0.
            assertEquals(0, r[1], "decode errors in clip: " + line + " (see [paper] log for problems)");
        }
    }
}
