package dev.zeffut.flashbackserver.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
        String body = http.send(HttpRequest.newBuilder(URI.create(buildsUrl)).build(),
            HttpResponse.BodyHandlers.ofString()).body();
        var builds = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("builds");
        JsonObject latest = builds.get(builds.size() - 1).getAsJsonObject();
        int buildNumber = latest.get("build").getAsInt();
        String fileName = latest.getAsJsonObject("downloads").getAsJsonObject("application")
            .get("name").getAsString();

        String dl = "https://api.papermc.io/v2/projects/paper/versions/" + VERSION
            + "/builds/" + buildNumber + "/downloads/" + fileName;
        http.send(HttpRequest.newBuilder(URI.create(dl)).build(),
            HttpResponse.BodyHandlers.ofFile(jar));
        return jar;
    }
}
