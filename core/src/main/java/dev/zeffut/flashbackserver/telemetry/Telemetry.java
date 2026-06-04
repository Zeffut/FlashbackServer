package dev.zeffut.flashbackserver.telemetry;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class Telemetry {

    private static final Gson GSON = new Gson();
    private static final String PLACEHOLDER_KEY = "phc_REPLACE_WITH_YOUR_KEY";

    private final boolean enabled;
    private final String host;
    private final String projectKey;
    private final String distinctId;
    private final String pluginVersion;
    private final Logger logger;
    private final HttpClient http;

    public Telemetry(boolean enabledConfig, String host, String projectKey,
                     String distinctId, String pluginVersion, Logger logger) {
        this.enabled = enabledConfig
                && projectKey != null
                && !projectKey.isBlank()
                && !projectKey.equals(PLACEHOLDER_KEY);
        this.host = host;
        this.projectKey = projectKey;
        this.distinctId = distinctId;
        this.pluginVersion = pluginVersion;
        this.logger = logger;
        this.http = HttpClient.newHttpClient();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Package-private: builds the JSON payload for a PostHog capture event.
     * No PII — distinct_id is a server-scoped random UUID.
     */
    String buildPayload(String event, Map<String, Object> props) {
        JsonObject properties = new JsonObject();
        // Add caller-supplied props first
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            properties.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
        }
        // Add standard library props
        properties.addProperty("$lib", "flashback-server");
        properties.addProperty("plugin_version", pluginVersion);

        JsonObject payload = new JsonObject();
        payload.addProperty("api_key", projectKey);
        payload.addProperty("event", event);
        payload.addProperty("distinct_id", distinctId);
        payload.add("properties", properties);

        return GSON.toJson(payload);
    }

    /**
     * Captures an event asynchronously. Never throws — telemetry must never affect the server.
     */
    public void capture(String event, Map<String, Object> props) {
        try {
            if (!enabled) return;
            String payload = buildPayload(event, props);
            HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/i/v0/e/"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(t -> { return null; }); // swallow — telemetry must never affect the server
        } catch (Throwable t) {
            // Swallow all errors: telemetry must never propagate into server threads
        }
    }

    /**
     * Loads (or creates) an anonymous server UUID from {@code dataFolder/.telemetry-id}.
     * Swallows all IO errors, returning a fresh random UUID if persistence fails.
     */
    public static String loadOrCreateDistinctId(Path dataFolder) {
        Path idFile = dataFolder.resolve(".telemetry-id");
        try {
            if (Files.exists(idFile)) {
                String content = Files.readString(idFile).trim();
                if (!content.isBlank()) {
                    return content;
                }
            }
            String newId = UUID.randomUUID().toString();
            Files.createDirectories(dataFolder);
            Files.writeString(idFile, newId);
            return newId;
        } catch (IOException e) {
            // Persistence failed; return a fresh UUID without rethrowing
            return UUID.randomUUID().toString();
        }
    }
}
