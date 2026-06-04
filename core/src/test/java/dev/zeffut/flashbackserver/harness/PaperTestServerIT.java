package dev.zeffut.flashbackserver.harness;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PaperTestServerIT {
    @Test
    void bootsAndStopsCleanly(@TempDir Path dir) throws Exception {
        try (PaperTestServer server = PaperTestServer.start(dir, 25601)) {
            assertTrue(server.runDir().resolve("plugins/FlashbackServer.jar").toFile().exists());
        }
    }
}
