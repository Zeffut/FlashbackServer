package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.regex.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test proving that a real recording's snapshot carries CONFIGURATION-phase packets
 * (registry data, features, tags) in addition to the PLAY-phase packets, and that ALL packets in
 * the file decode cleanly through their respective codecs.
 *
 * <p>Key assertions:
 * <ul>
 *   <li>{@code errors == 0} — every config + game packet in the file round-trips through the
 *       Minecraft codec without a decode error or trailing bytes.</li>
 *   <li>{@code decoded >= 25} — the snapshot includes at least ~24 config-phase packets
 *       (features + select-known-packs + 21 registry-data + tags) plus PLAY-phase packets,
 *       confirming the snapshot is not bare.</li>
 *   <li>{@link FlashbackValidator#validateRenderable(Path)} returns {@code valid=true} — the
 *       snapshot now satisfies the updated renderable floor including configuration data.</li>
 * </ul>
 */
@Tag("integration")
class SnapshotFidelityIT {

    private static final int DECODED_FLOOR = 25; // 24 config + ≥1 game packet at minimum

    private static int[] parseVerify(String line) { // returns {decoded, errors}
        Matcher m = Pattern.compile("decoded=(\\d+) errors=(\\d+)").matcher(line);
        assertTrue(m.find(), "could not parse verify line: " + line);
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    @Test
    void snapshotCarriesConfigDataAndDecodesCleanly(@TempDir Path dir) throws Exception {
        int port = 25615;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            String file;
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "FidelityBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join within timeout");
                server.sendCommand("replay start players FidelityBot");
                assertTrue(server.awaitLogLine("Recording FidelityBot", 15), "start not confirmed");
                Thread.sleep(4000); // accrue ticks + packets so the snapshot is flushed
                server.sendCommand("replay stop players FidelityBot");
                assertTrue(server.awaitLogLine("Saved replay:", 20), "stop not confirmed");
            }

            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            Path replay;
            try (Stream<Path> files = Files.list(replays)) {
                replay = files
                    .filter(p -> p.toString().endsWith(".flashback"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no .flashback produced in " + replays));
            }
            file = replay.getFileName().toString();

            // Run the verifier via the /replay verify command.
            server.sendCommand("replay verify " + file);
            String line = server.awaitLogLineContaining("Verify " + file + ":", 30);
            assertNotNull(line, "verify command did not produce output");

            int[] r = parseVerify(line);
            int decoded = r[0];
            int errors  = r[1];

            // Core assertion: all packets (config + game) decode with zero errors.
            assertEquals(0, errors,
                "decode errors found — config or game packets corrupted: " + line
                + " (check [paper] log for 'Verify problem:' lines)");

            // Snapshot-size floor: at least 25 packets confirms config phase is present.
            assertTrue(decoded >= DECODED_FLOOR,
                "decoded=" + decoded + " is below floor of " + DECODED_FLOOR
                + " — snapshot appears to be missing configuration data: " + line);

            // validateRenderable must pass (now requires ≥1 configuration_packet).
            FlashbackValidator.Report report = FlashbackValidator.validateRenderable(replay);
            assertTrue(report.valid(),
                "validateRenderable failed — snapshot missing required elements: "
                + report.problems());
        }
    }
}
