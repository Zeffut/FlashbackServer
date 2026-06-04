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
        Path jar = targetDir.resolve(project + ".jar");
        if (Files.exists(jar)) return jar;

        HttpClient http = HttpClient.newHttpClient();
        String buildsUrl = "https://api.papermc.io/v2/projects/" + project + "/versions/" + version + "/builds";
        HttpResponse<String> buildsResp = http.send(HttpRequest.newBuilder(URI.create(buildsUrl)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (buildsResp.statusCode() != 200) {
            throw new IOException(project + " builds API returned " + buildsResp.statusCode() + " for " + buildsUrl);
        }
        var builds = JsonParser.parseString(buildsResp.body()).getAsJsonObject().getAsJsonArray("builds");
        JsonObject latest = builds.get(builds.size() - 1).getAsJsonObject();
        int buildNumber = latest.get("build").getAsInt();
        String fileName = latest.getAsJsonObject("downloads").getAsJsonObject("application")
            .get("name").getAsString();

        String dl = "https://api.papermc.io/v2/projects/" + project + "/versions/" + version
            + "/builds/" + buildNumber + "/downloads/" + fileName;
        // Download to a temp file and atomically move into place only on success, so a failed
        // download never leaves a corrupt jar that the cache check would then trust.
        Path tmp = targetDir.resolve(project + ".jar.tmp");
        try {
            HttpResponse<Path> dlResp = http.send(HttpRequest.newBuilder(URI.create(dl)).build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
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
