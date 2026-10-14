package dev.zeffut.flashbackserver.telemetry;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP only to loopback, fictional key. Never uses the shipped ingestion key. */
class TelemetryRegionPrivacyTest {
    @Test void shippedDefaultsAreEuAndOperatorOverridesSurviveReload() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals("https://eu.i.posthog.com", cfg.getString("telemetry.posthog.host"));
            assertTrue(cfg.getBoolean("telemetry.enabled"), "do not silently change existing consent default");
            cfg.set("telemetry.posthog.host", "http://127.0.0.1:1");
            cfg.set("telemetry.enabled", false);
            var reloaded = new YamlConfiguration();
            reloaded.loadFromString(cfg.saveToString());
            assertEquals("http://127.0.0.1:1", reloaded.getString("telemetry.posthog.host"));
            assertFalse(reloaded.getBoolean("telemetry.enabled"));
        }
    }

    @Test void localCaptureMatchesContractAndDisabledClientSendsNothing() throws Exception {
        var requests = new LinkedBlockingQueue<Map.Entry<String, String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(Map.entry(exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            var disabled = new Telemetry(false, host, "test-ingestion-key", "fixture-id", "test", Logger.getLogger("test"));
            disabled.capture("not_sent", Map.of());
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS));
            var enabled = new Telemetry(true, host, "test-ingestion-key", "fixture-id", "test", Logger.getLogger("test"));
            enabled.capture("fixture_event", Map.of("count", 1));
            var request = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/i/v0/e/", request.getKey());
            var json = JsonParser.parseString(request.getValue()).getAsJsonObject();
            assertEquals("fixture_event", json.get("event").getAsString());
            assertEquals("fixture-id", json.get("distinct_id").getAsString());
            assertEquals("test-ingestion-key", json.get("api_key").getAsString());
            assertEquals(1, json.getAsJsonObject("properties").get("count").getAsInt());
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS));
        } finally { server.stop(0); }
    }
}
