package dev.zeffut.flashbackserver.version;

import dev.zeffut.flashbackserver.harness.PaperTestServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves multi-version support at the adapter level: boots a Paper server on a DIFFERENT Minecraft
 * version (1.21.8) than this build's "current" (1.21.5) and confirms the bundled jar loads and the
 * correct version adapter (the reobf'd 1.21.8 NMS module) is selected and instantiates successfully
 * on that version.
 *
 * <p>Why no bot/recording here: the test harness's bot ({@code mcprotocollib:protocol:1.21.5-1})
 * speaks the 1.21.5 protocol and is rejected by a 1.21.8 server ("Outdated client"). A full
 * record-with-bot proof on 1.21.8 would need a 1.21.8-protocol bot (separate classpath) — a future
 * harness improvement. The adapter selecting + instantiating on 1.21.8 (which touches the reobf'd
 * 1.21.8 NMS) is the meaningful cross-version proof available here.
 */
@Tag("integration")
class CrossVersionRecordingIT {

    @Test
    void selectsThe1_21_8AdapterOnA1_21_8Server(@TempDir Path dir) throws Exception {
        int port = 25617;
        try (PaperTestServer server = PaperTestServer.start(dir, port, "paper", "1.21.8")) {
            // The plugin enabled on the 1.21.8 server...
            assertTrue(server.awaitLogLine("FlashbackServer enabled.", 60),
                "plugin did not enable on the 1.21.8 server");
            // ...and selected + instantiated the 1.21.8 adapter (touches the reobf'd 1.21.8 NMS).
            assertTrue(server.awaitLogLine("Version adapter: V1_21_8Adapter", 10),
                "the v1_21_8 adapter was not selected/instantiated on the 1.21.8 server");
        }
    }
}
