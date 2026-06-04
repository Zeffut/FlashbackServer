package dev.zeffut.flashbackserver.telemetry;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryTest {
    private Telemetry enabled() {
        return new Telemetry(true, "https://us.i.posthog.com", "phc_testkey123",
            "server-uuid-abc", "0.1.0", Logger.getLogger("test"));
    }

    @Test
    void buildPayloadHasApiKeyEventDistinctIdAndProps() {
        String json = enabled().buildPayload("plugin_enabled", Map.of("platform", "Paper", "mc_version", "1.21.5"));
        assertTrue(json.contains("phc_testkey123"));
        assertTrue(json.contains("\"event\""));
        assertTrue(json.contains("plugin_enabled"));
        assertTrue(json.contains("\"distinct_id\""));
        assertTrue(json.contains("server-uuid-abc"));
        assertTrue(json.contains("\"properties\""));
        assertTrue(json.contains("\"platform\""));
        assertTrue(json.contains("Paper"));
        assertTrue(json.contains("flashback-server")); // $lib
    }

    @Test
    void disabledIsNoOp() {
        Telemetry t = new Telemetry(false, "https://us.i.posthog.com", "phc_x", "id", "0.1.0", Logger.getLogger("test"));
        assertFalse(t.isEnabled());
        assertDoesNotThrow(() -> t.capture("e", Map.of("a", 1))); // no send, no throw
    }

    @Test
    void blankOrPlaceholderKeyDisables() {
        assertFalse(new Telemetry(true, "h", "", "id", "0.1.0", Logger.getLogger("test")).isEnabled());
        assertFalse(new Telemetry(true, "h", "phc_REPLACE_WITH_YOUR_KEY", "id", "0.1.0", Logger.getLogger("test")).isEnabled());
    }

    @Test
    void payloadOnlyContainsProvidedProps() {
        String json = enabled().buildPayload("recording_saved", Map.of("tick_count", 40, "file_bytes", 12345));
        assertTrue(json.contains("tick_count"));
        assertTrue(json.contains("file_bytes"));
        // sanity: no accidental player key
        assertFalse(json.toLowerCase().contains("playername"));
    }
}
