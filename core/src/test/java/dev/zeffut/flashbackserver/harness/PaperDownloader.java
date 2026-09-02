package dev.zeffut.flashbackserver.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

public final class PaperDownloader {
    private PaperDownloader() {}

    /**
     * Convenience overload: downloads the latest Paper 1.21.5 build into
     * {@code <targetDir>/paper.jar}, caching on disk.
     */
    public static Path resolve(Path targetDir) throws Exception {
        return resolve(targetDir, "paper", "1.21.5");
    }

    /**
     * Downloads the latest build of {@code project}/{@code version} from the PaperMC v2 API into
     * {@code <targetDir>/<project>.jar}, caching on disk (re-downloads only if the file is absent).
     * Uses an atomic temp-file rename so a failed download never leaves a corrupt jar.
     *
     * @param targetDir directory to place the jar in (created if absent)
     * @param project   PaperMC project name, e.g. {@code "paper"} or {@code "folia"}
     * @param version   Minecraft version string, e.g. {@code "1.21.5"}
     * @return path to the downloaded jar
     */
    public static Path resolve(Path targetDir, String project, String version) throws Exception {
        Files.createDirectories(targetDir);
        Path jar = targetDir.resolve(project + "-" + version + ".jar");
        if (Files.exists(jar)) return jar;

        HttpClient http = HttpClient.newHttpClient();
        String buildsUrl = "https://fill.papermc.io/v3/projects/" + project
                + "/versions/" + version + "/builds";
        HttpRequest buildsRequest = HttpRequest.newBuilder(URI.create(buildsUrl))
                .header("User-Agent", "FlashbackServer-TestHarness/1.2 (https://github.com/Zeffut/FlashbackServer)")
                .build();
        HttpResponse<String> buildsResp = http.send(buildsRequest, HttpResponse.BodyHandlers.ofString());
        if (buildsResp.statusCode() != 200) {
            throw new IOException(project + " builds API returned " + buildsResp.statusCode() + " for " + buildsUrl);
        }
        var builds = JsonParser.parseString(buildsResp.body()).getAsJsonArray();
        JsonObject latest = null;
        for (var element : builds) {
            JsonObject candidate = element.getAsJsonObject();
            if ("STABLE".equals(candidate.get("channel").getAsString())) {
                latest = candidate;
                break;
            }
        }
        if (latest == null && !builds.isEmpty()) {
            // Archived versions such as 1.21.5 predate Fill's STABLE channel.
            // The API returns builds newest-first, so use the newest available build for
            // this test harness only when no stable channel exists.
            latest = builds.get(0).getAsJsonObject();
        }
        if (latest == null) {
            throw new IOException("No " + project + " build for Minecraft " + version);
        }
        String dl = latest.getAsJsonObject("downloads").getAsJsonObject("server:default")
                .get("url").getAsString();
        // Download to a temp file and atomically move into place only on success, so a failed
        // download never leaves a corrupt jar that the cache check would then trust.
        Path tmp = targetDir.resolve(project + "-" + version + ".jar.tmp");
        try {
            HttpRequest downloadRequest = HttpRequest.newBuilder(URI.create(dl))
                    .header("User-Agent", "FlashbackServer-TestHarness/1.2 (https://github.com/Zeffut/FlashbackServer)")
                    .build();
            HttpResponse<Path> dlResp = http.send(downloadRequest, HttpResponse.BodyHandlers.ofFile(tmp));
            if (dlResp.statusCode() != 200) {
                throw new IOException(project + " download returned " + dlResp.statusCode() + " for " + dl);
            }
            Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return jar;
    }
}
