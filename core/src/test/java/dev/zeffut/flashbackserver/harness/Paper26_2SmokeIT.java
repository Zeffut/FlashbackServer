package dev.zeffut.flashbackserver.harness;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the deployed shaded plugin with the Java-25 Paper 26.2 runtime. */
@Tag("integration")
class Paper26_2SmokeIT {
    @Test
    void bootsLoadsPluginAndStopsCleanly(@TempDir Path dir) throws Exception {
        try (PaperTestServer server = PaperTestServer.start(dir, 25603, "paper", "26.2")) {
            assertTrue(server.awaitLogLine("FlashbackServer enabled.", 30),
                    "FlashbackServer did not report successful enablement");
        }
    }
}