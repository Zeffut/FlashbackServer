package dev.zeffut.flashbackserver.harness;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

public final class PaperTestServer implements AutoCloseable {
    private final Process process;
    private final Path runDir;

    private PaperTestServer(Process process, Path runDir) {
        this.process = process;
        this.runDir = runDir;
    }

    /** Boots Paper with the plugin at flashback.plugin.jar on the given port; waits for readiness. */
    public static PaperTestServer start(Path baseDir, int port) throws Exception {
        Path runDir = baseDir.resolve("run");
        Files.createDirectories(runDir.resolve("plugins"));
        Files.writeString(runDir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(runDir.resolve("server.properties"),
            "online-mode=false\nserver-port=" + port + "\nlevel-type=minecraft\\:flat\nspawn-protection=0\n");

        String pluginJar = System.getProperty("flashback.plugin.jar");
        if (pluginJar == null) throw new IllegalStateException("flashback.plugin.jar system property not set");
        Files.copy(Path.of(pluginJar), runDir.resolve("plugins").resolve("FlashbackServer.jar"),
            StandardCopyOption.REPLACE_EXISTING);

        Path paperJar = PaperDownloader.resolve(baseDir.resolve("test-server"));

        ProcessBuilder pb = new ProcessBuilder(
            "java", "-Xmx1G", "-jar", paperJar.toAbsolutePath().toString(), "nogui")
            .directory(runDir.toFile())
            .redirectErrorStream(true);
        Process process = pb.start();

        CountDownLatch ready = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[paper] " + line);
                    if (line.contains("Done (")) ready.countDown();
                }
            } catch (IOException ignored) {}
        });
        reader.setDaemon(true);
        reader.start();

        if (!ready.await(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Paper server did not become ready within 180s");
        }
        return new PaperTestServer(process, runDir);
    }

    public Path runDir() { return runDir; }

    @Override public void close() throws Exception {
        try (var w = new OutputStreamWriter(process.getOutputStream())) {
            w.write("stop\n");
            w.flush();
        } catch (IOException ignored) {}
        if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly();
    }
}
