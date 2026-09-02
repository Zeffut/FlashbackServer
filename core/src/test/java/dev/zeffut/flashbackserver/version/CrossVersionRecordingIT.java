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
    void selectsThe1_21_5AdapterOnA1_21_5Server(@TempDir Path dir) throws Exception {
        assertAdapterSelected(dir, 25615, "1.21.5", "V1_21_5Adapter");
    }

    @Test
    void selectsThe1_21_6AdapterOnA1_21_6Server(@TempDir Path dir) throws Exception {
        assertAdapterSelected(dir, 25616, "1.21.6", "V1_21_6Adapter");
    }

    @Test
    void selectsThe1_21_7AdapterOnA1_21_7Server(@TempDir Path dir) throws Exception {
        assertAdapterSelected(dir, 25617, "1.21.7", "V1_21_7Adapter");
    }

    @Test
    void selectsThe1_21_8AdapterOnA1_21_8Server(@TempDir Path dir) throws Exception {
        assertAdapterSelected(dir, 25618, "1.21.8", "V1_21_8Adapter");
    }

    @Test
    void selectsThe1_21_9AdapterOnA1_21_9Server(@TempDir Path dir) throws Exception {
        assertAdapterSelected(dir, 25619, "1.21.9", "V1_21_9Adapter");
    }

    @Test
    void selectsThe1_21_10AdapterOnA1_21_10Server(@TempDir Path dir) throws Exception {
        assertAdapterSelected(dir, 25620, "1.21.10", "V1_21_10Adapter");
    }

    @Test
    void selectsThe1_21_11AdapterOnA1_21_11Server(@TempDir Path dir) throws Exception {
        assertAdapterSelected(dir, 25621, "1.21.11", "V1_21_11Adapter");
    }

    @Test
    void selectsThe26_2AdapterOnA26_2Server(@TempDir Path dir) throws Exception {
        assertAdapterSelected(dir, 25622, "26.2", "V26_2Adapter");
    }

    private static void assertAdapterSelected(Path dir, int port, String version, String adapterClass)
            throws Exception {
        try (PaperTestServer server = PaperTestServer.start(dir, port, "paper", version)) {
            // The bundled jar loads + the plugin enables on this version...
            assertTrue(server.awaitLogLine("FlashbackServer enabled.", 90),
                "plugin did not enable on the " + version + " server");
            // ...and the matching version adapter (its reobf'd NMS) is selected + instantiated.
            assertTrue(server.awaitLogLine("Version adapter: " + adapterClass, 10),
                "the " + adapterClass + " was not selected/instantiated on the " + version + " server");
        }
    }
}
