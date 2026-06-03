package dev.zeffut.flashbackserver.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

public final class PaperDownloader {
    private static final String VERSION = "1.21.5";
    private PaperDownloader() {}

    /** Returns the path to a Paper jar, downloading the latest build once if absent. */
    public static Path resolve(Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        Path jar = targetDir.resolve("paper.jar");
        if (Files.exists(jar)) return jar;

        HttpClient http = HttpClient.newHttpClient();
        String buildsUrl = "https://api.papermc.io/v2/projects/paper/versions/" + VERSION + "/builds";
        HttpResponse<String> buildsResp = http.send(HttpRequest.newBuilder(URI.create(buildsUrl)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (buildsResp.statusCode() != 200) {
            throw new IOException("Paper builds API returned " + buildsResp.statusCode() + " for " + buildsUrl);
        }
        var builds = JsonParser.parseString(buildsResp.body()).getAsJsonObject().getAsJsonArray("builds");
        JsonObject latest = builds.get(builds.size() - 1).getAsJsonObject();
        int buildNumber = latest.get("build").getAsInt();
        String fileName = latest.getAsJsonObject("downloads").getAsJsonObject("application")
            .get("name").getAsString();

        String dl = "https://api.papermc.io/v2/projects/paper/versions/" + VERSION
            + "/builds/" + buildNumber + "/downloads/" + fileName;
        // Download to a temp file and atomically move into place only on success, so a failed
        // download never leaves a corrupt paper.jar that the cache check would then trust.
        Path tmp = targetDir.resolve("paper.jar.tmp");
        try {
            HttpResponse<Path> dlResp = http.send(HttpRequest.newBuilder(URI.create(dl)).build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
            if (dlResp.statusCode() != 200) {
                throw new IOException("Paper download returned " + dlResp.statusCode() + " for " + dl);
            }
            Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return jar;
    }
}
