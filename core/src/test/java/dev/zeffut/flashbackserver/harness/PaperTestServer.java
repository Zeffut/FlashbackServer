package dev.zeffut.flashbackserver.harness;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PaperTestServer implements AutoCloseable {
    private final Process process;
    private final Path runDir;
    private final List<String> logLines = new CopyOnWriteArrayList<>();

    private PaperTestServer(Process process, Path runDir) {
        this.process = process;
        this.runDir = runDir;
    }

    /**
     * Boots Paper (project {@code "paper"}) with the plugin on the given port; waits for readiness.
     * Delegates to {@link #start(Path, int, String)}.
     */
    public static PaperTestServer start(Path baseDir, int port) throws Exception {
        return start(baseDir, port, "paper");
    }

    /**
     * Boots the specified PaperMC-compatible server ({@code "paper"}, {@code "folia"}, …) with the
     * plugin on the given port; waits for readiness. The jar is resolved via
     * {@link PaperDownloader#resolve(Path, String, String)} with version {@code "1.21.5"} and cached
     * under {@code <baseDir>/test-server/<project>.jar}.
     *
     * @param baseDir working base directory (a {@code run/} sub-directory is created inside)
     * @param port    server port
     * @param project PaperMC project name, e.g. {@code "paper"} or {@code "folia"}
     */
    public static PaperTestServer start(Path baseDir, int port, String project) throws Exception {
        Path runDir = baseDir.resolve("run");
        Files.createDirectories(runDir.resolve("plugins"));
        Files.writeString(runDir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(runDir.resolve("server.properties"),
            "online-mode=false\nserver-port=" + port + "\nlevel-type=minecraft\\:flat\nspawn-protection=0\n");

        // Disable telemetry in integration tests so no HTTP POSTs are made to PostHog.
        // saveDefaultConfig() won't overwrite an existing file, so writing this before server start
        // ensures telemetry.enabled=false for all ITs.
        Path pluginConfigDir = runDir.resolve("plugins").resolve("FlashbackServer");
        Files.createDirectories(pluginConfigDir);
        Files.writeString(pluginConfigDir.resolve("config.yml"),
            "telemetry:\n  enabled: false\n");

        String pluginJar = System.getProperty("flashback.plugin.jar");
        if (pluginJar == null) throw new IllegalStateException("flashback.plugin.jar system property not set");
        Files.copy(Path.of(pluginJar), runDir.resolve("plugins").resolve("FlashbackServer.jar"),
            StandardCopyOption.REPLACE_EXISTING);

        Path serverJar = PaperDownloader.resolve(baseDir.resolve("test-server"), project, "1.21.5");

        ProcessBuilder pb = new ProcessBuilder(
            "java", "-Xmx1G", "-jar", serverJar.toAbsolutePath().toString(), "nogui")
            .directory(runDir.toFile())
            .redirectErrorStream(true);
        Process process = pb.start();

        CountDownLatch ready = new CountDownLatch(1);
        PaperTestServer server = new PaperTestServer(process, runDir);
        String prefix = "[" + project + "] ";
        Thread reader = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println(prefix + line);
                    server.logLines.add(line);
                    if (line.contains("Done (")) ready.countDown();
                }
            } catch (IOException ignored) {}
        });
        reader.setDaemon(true);
        reader.start();

        if (!ready.await(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(project + " server did not become ready within 180s");
        }
        return server;
    }

    public Path runDir() { return runDir; }

    /**
     * Polls accumulated server log lines until one contains {@code substring} or the timeout
     * elapses. Returns {@code true} if found, {@code false} on timeout.
     */
    public boolean awaitLogLine(String substring, long timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (String line : logLines) {
                if (line.contains(substring)) return true;
            }
            Thread.sleep(100);
        }
        // one final check after deadline
        for (String line : logLines) {
            if (line.contains(substring)) return true;
        }
        return false;
    }

    /**
     * Polls accumulated server log lines until one contains {@code substring} or the timeout
     * elapses. Returns the first full line containing {@code substring}, or {@code null} on timeout.
     */
    public String awaitLogLineContaining(String substring, long timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (String line : logLines) {
                if (line.contains(substring)) return line;
            }
            Thread.sleep(100);
        }
        // one final check after deadline
        for (String line : logLines) {
            if (line.contains(substring)) return line;
        }
        return null;
    }

    /**
     * Scans accumulated log lines for any containing {@code "[capture] "}, parses the integer
     * after {@code "packets="}, and returns the maximum found (0 if none).
     */
    public long maxCapturedCount() {
        long max = 0;
        for (String line : logLines) {
            if (!line.contains("[capture] ")) continue;
            int idx = line.indexOf("packets=");
            if (idx < 0) continue;
            try {
                String rest = line.substring(idx + "packets=".length()).trim();
                // take only leading digits
                int end = 0;
                while (end < rest.length() && Character.isDigit(rest.charAt(end))) end++;
                if (end > 0) {
                    long val = Long.parseLong(rest.substring(0, end));
                    if (val > max) max = val;
                }
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    /** Sends a console command to the running server by writing to its stdin. */
    public void sendCommand(String command) {
        try {
            OutputStream os = process.getOutputStream();
            os.write((command + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException ignored) {}
    }

    @Override public void close() throws Exception {
        sendCommand("stop");
        if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly();
    }
}
