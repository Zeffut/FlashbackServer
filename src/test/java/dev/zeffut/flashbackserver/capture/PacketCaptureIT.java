package dev.zeffut.flashbackserver.capture;

import dev.zeffut.flashbackserver.harness.BotClient;
import dev.zeffut.flashbackserver.harness.PaperTestServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PacketCaptureIT {
    @Test
    void capturesOutboundPacketsForJoinedBot(@TempDir Path dir) throws Exception {
        int port = 25603;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "CaptureBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                Thread.sleep(3000); // let the server stream play packets to the bot
            } // bot.close() disconnects -> server fires PlayerQuitEvent -> logs the [capture] line
            assertTrue(server.awaitLogLine("[capture] CaptureBot packets=", 30),
                "no capture log line observed");
            assertTrue(server.maxCapturedCount() > 0, "captured zero packets");
        }
    }
}
