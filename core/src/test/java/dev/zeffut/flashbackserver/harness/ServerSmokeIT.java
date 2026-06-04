package dev.zeffut.flashbackserver.harness;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ServerSmokeIT {
    @Test
    void botJoinsRunningServer(@TempDir Path dir) throws Exception {
        int port = 25602;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "TestBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not receive the join packet within 60s");
            }
        }
    }
}
