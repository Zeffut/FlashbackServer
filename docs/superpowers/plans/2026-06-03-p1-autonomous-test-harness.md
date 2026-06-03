# Phase 1 — Autonomous Test Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the autonomous integration-test harness that boots a real Paper 1.21.x
server with our plugin, drives it with a scripted headless bot client, and tears it down
cleanly — so every later phase can be verified without a human. The harness reuses
`FlashbackValidator` (P0) as its correctness oracle once recordings exist (P2+).

**Architecture:** Two layers of tests. (1) Fast unit tests (existing JUnit + MockBukkit
for Bukkit-facing logic in later phases). (2) A heavyweight `@Tag("integration")` suite,
run by a dedicated Gradle `integrationTest` task that depends on the built plugin jar. The
integration layer is built from a `PaperTestServer` utility (launches the Paper server jar
as a subprocess, awaits readiness, stops cleanly) and a `BotClient` utility (an
MCProtocolLib client that logs in offline, performs scripted actions, disconnects).

**Tech Stack:** Java 21, Gradle (Kotlin DSL), paperweight-userdev 2.0.0-beta.18,
`xyz.jpenilla.run-paper` 3.0.2 (dev convenience), `org.geysermc.mcprotocollib:protocol:1.21.4-1`
(headless bot), JUnit 5. No ProtocolLib. (MockBukkit arrives in P2 for fast unit tests.)

---

## Environmental risk (read first)

Unlike P0 (pure Java), this phase depends on the runtime being able to:
- download and boot a Paper 1.21.4 server jar headless (Java 21, network access),
- bind a local TCP port and accept an offline-mode login,
- run for ~30–60s per integration test.

**Task 1 is a go/no-go feasibility spike.** Do NOT build the rest of the harness until the
spike proves a server can boot here. If the spike is BLOCKED (no network, sandboxed ports,
etc.), STOP and report — the harness design will need to change (e.g. fall back to
MockBukkit + a Netty-pipeline unit harness), which is a re-plan, not a workaround.

## File structure (created by this plan)

- `build.gradle.kts` — add run-paper plugin, opencollab repo, MockBukkit + MCProtocolLib test deps, `integrationTest` task
- `run/` — gitignored server run directory (eula.txt, server.properties) — created by harness
- `src/test/java/dev/zeffut/flashbackserver/harness/PaperTestServer.java` — server lifecycle utility
- `src/test/java/dev/zeffut/flashbackserver/harness/PaperDownloader.java` — fetch a Paper 1.21.4 jar
- `src/test/java/dev/zeffut/flashbackserver/harness/BotClient.java` — MCProtocolLib bot utility
- `src/test/java/dev/zeffut/flashbackserver/harness/ServerSmokeIT.java` — end-to-end smoke test (`@Tag("integration")`)
- `docs/test-harness.md` — how to run unit vs integration tests

---

### Task 1: Feasibility spike — boot Paper 1.21.4 headless (GO/NO-GO)

Prove a Paper server can boot here with our plugin before building anything else.

**Files:**
- Modify: `build.gradle.kts` (add run-paper plugin + runServer config)
- Create: `docs/test-harness.md` (spike findings section)

- [ ] **Step 1: Add the run-paper plugin and configure runServer**

In `build.gradle.kts`, add to the `plugins {}` block:
```kotlin
id("xyz.jpenilla.run-paper") version "3.0.2"
```
And add at the end of the file:
```kotlin
tasks.runServer {
    minecraftVersion("1.21.4")
}
```

- [ ] **Step 2: Pre-accept EULA and set offline mode for the run directory**

Create `run/eula.txt` with `eula=true` and `run/server.properties` with at least:
```properties
online-mode=false
server-port=25599
level-type=minecraft\:flat
spawn-protection=0
```
(run-paper uses the `run/` directory by default. These avoid the interactive EULA prompt
and a Mojang auth requirement.)

- [ ] **Step 3: Boot the server with a forced auto-stop and capture the log**

Run (boots, then pipes `stop` after the server is up; kill-guarded by a timeout):
```bash
( sleep 45; echo stop ) | timeout 180 ./gradlew runServer --console=plain 2>&1 | tee run-spike.log | tail -40
```
Inspect `run-spike.log`. Confirm BOTH appear:
- a line containing `Done (` (server finished startup)
- `FlashbackServer enabled.` (our plugin loaded)

- [ ] **Step 4: Record the verdict**

Create `docs/test-harness.md` with a `## Phase 1 feasibility spike` section stating:
- whether the server reached `Done (`,
- whether the plugin loaded,
- approximate cold-boot time,
- any blockers (network, port, mappings download).

If the server did NOT boot, STOP and report **BLOCKED** with the tail of `run-spike.log`.
Do not proceed to Task 2.

- [ ] **Step 5: Commit**

```bash
echo "run-spike.log" >> .gitignore
git add build.gradle.kts docs/test-harness.md .gitignore
git -c commit.gpgsign=false commit -m "build: add run-paper; spike confirms Paper 1.21.4 boots headless with plugin"
```

---

### Task 2: `PaperDownloader` + `PaperTestServer` lifecycle utility

A test utility that resolves a Paper 1.21.4 jar and runs it as a controllable subprocess.

**Files:**
- Add test deps + `integrationTest` task to `build.gradle.kts`
- Create: `src/test/java/dev/zeffut/flashbackserver/harness/PaperDownloader.java`
- Create: `src/test/java/dev/zeffut/flashbackserver/harness/PaperTestServer.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/harness/PaperTestServerIT.java`

- [ ] **Step 1: Add test dependencies and the integrationTest task to `build.gradle.kts`**

Add the opencollab repository to `repositories {}`:
```kotlin
maven("https://repo.opencollab.dev/main/")
```
Add to `dependencies {}`:
```kotlin
testImplementation("org.geysermc.mcprotocollib:protocol:1.21.4-1")
```
(MockBukkit is intentionally NOT added here — no P1 test needs it. It arrives in P2 with the
first fast unit test of Bukkit-facing logic.)
Add a tag-segregated integration task (so `./gradlew test` stays fast and offline):
```kotlin
tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}
val integrationTest by tasks.registering(Test::class) {
    useJUnitPlatform { includeTags("integration") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // The built, reobfuscated plugin jar is passed to the harness.
    dependsOn(tasks.reobfJar)
    systemProperty("flashback.plugin.jar", tasks.reobfJar.get().outputJar.get().asFile.absolutePath)
    shouldRunAfter(tasks.test)
}
```
Run `./gradlew help` (or `./gradlew tasks`) to confirm the build script still configures
without error. (If `reobfJar`/`outputJar` differs in paperweight 2.0.0-beta.18, adjust to the
correct task/property that produces the runnable plugin jar — confirm with `./gradlew tasks --all | grep -i jar`.)

- [ ] **Step 2: Write `PaperDownloader`**

Resolves a Paper 1.21.4 jar into `build/test-server/paper.jar`, downloading the latest build
via the PaperMC v2 API only if not already present.
```java
package dev.zeffut.flashbackserver.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

public final class PaperDownloader {
    private static final String VERSION = "1.21.4";
    private PaperDownloader() {}

    /** Returns the path to a Paper jar, downloading it once if absent. */
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
```

- [ ] **Step 3: Write `PaperTestServer`**

Boots the jar as a subprocess in a fresh run dir, copies the plugin jar into `plugins/`,
writes eula + offline server.properties, and blocks until the server logs readiness.
```java
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
```

- [ ] **Step 4: Write `PaperTestServerIT` (the harness's own smoke test)**

```java
package dev.zeffut.flashbackserver.harness;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PaperTestServerIT {
    @Test
    void bootsAndStopsCleanly(@TempDir Path dir) throws Exception {
        try (PaperTestServer server = PaperTestServer.start(dir, 25601)) {
            assertTrue(server.runDir().resolve("plugins/FlashbackServer.jar").toFile().exists());
        }
        // close() returned without throwing → clean shutdown
    }
}
```

- [ ] **Step 5: Run the integration test**

Run: `./gradlew integrationTest --tests '*PaperTestServerIT'`
Expected: PASS (downloads Paper once, boots, plugin jar present, stops cleanly). Allow several minutes.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/test/java/dev/zeffut/flashbackserver/harness/PaperDownloader.java \
        src/test/java/dev/zeffut/flashbackserver/harness/PaperTestServer.java \
        src/test/java/dev/zeffut/flashbackserver/harness/PaperTestServerIT.java
git -c commit.gpgsign=false commit -m "test: add Paper subprocess harness with integrationTest task"
```

---

### Task 3: `BotClient` — headless MCProtocolLib login

A utility that connects to the test server as an offline player and confirms login.

**Files:**
- Create: `src/test/java/dev/zeffut/flashbackserver/harness/BotClient.java`

- [ ] **Step 1: Write `BotClient`**

Minimal wrapper around an MCProtocolLib session: connect, await the play phase, disconnect.
```java
package dev.zeffut.flashbackserver.harness;

import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import java.net.InetSocketAddress;
import java.util.concurrent.*;

public final class BotClient implements AutoCloseable {
    private final ClientSession session;
    private final CountDownLatch joined = new CountDownLatch(1);

    private BotClient(ClientSession session) { this.session = session; }

    /** Connects as an offline player with the given name and waits for the join (login) packet. */
    public static BotClient connect(String host, int port, String username) throws Exception {
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        ClientSession session = ClientNetworkSessionFactory.factory()
            .setRemoteSocketAddress(new InetSocketAddress(host, port))
            .setProtocol(protocol)
            .create();
        BotClient bot = new BotClient(session);
        session.addListener(new SessionAdapter() {
            @Override public void packetReceived(org.geysermc.mcprotocollib.network.Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) bot.joined.countDown();
            }
        });
        session.connect();
        return bot;
    }

    /** Blocks until the bot has joined the game, or fails after the timeout. */
    public boolean awaitJoin(long timeoutSeconds) throws InterruptedException {
        return joined.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    @Override public void close() {
        session.disconnect("bye");
    }
}
```
NOTE: the exact MCProtocolLib API (factory name, listener signatures, packet class names) may
differ slightly in `1.21.4-1`. If a symbol does not resolve, consult the MCProtocolLib
`example/` sources for that version and adjust the calls to compile — keep the same behavior
(connect offline, signal on the clientbound login/join packet, disconnect). Do not change the
public method shape (`connect`, `awaitJoin`, `close`).

- [ ] **Step 2: Compile the test sources to verify the API usage**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL. If it fails on MCProtocolLib symbols, fix the calls per the note
above until it compiles, then re-run.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/zeffut/flashbackserver/harness/BotClient.java
git -c commit.gpgsign=false commit -m "test: add headless MCProtocolLib bot client utility"
```

---

### Task 4: End-to-end smoke integration test

Prove the full loop: boot server + plugin, bot logs in, clean teardown.

**Files:**
- Create: `src/test/java/dev/zeffut/flashbackserver/harness/ServerSmokeIT.java`
- Modify: `docs/test-harness.md` (usage section)

- [ ] **Step 1: Write `ServerSmokeIT`**

```java
package dev.zeffut.flashbackserver.harness;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ServerSmokeIT {
    @Test
    void botJoinsRunningServer(@TempDir Path dir) throws Exception {
        int port = 25602;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "TestBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not receive the join packet within 60s");
            }
        }
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `./gradlew integrationTest --tests '*ServerSmokeIT'`
Expected: PASS — server boots, bot joins, both shut down cleanly.

- [ ] **Step 3: Document how to run the suites in `docs/test-harness.md`**

Append a `## Running tests` section:
```markdown
## Running tests
- Fast unit tests (no network, no server): `./gradlew test`
- Integration tests (boots a real Paper server + headless bot): `./gradlew integrationTest`
  - Downloads a Paper 1.21.4 jar once into `build/test-server/`.
  - Requires network access and a free local port.
```

- [ ] **Step 4: Run the full suites and commit**

Run: `./gradlew test integrationTest`
Expected: unit tests PASS; integration tests PASS.
```bash
git add src/test/java/dev/zeffut/flashbackserver/harness/ServerSmokeIT.java docs/test-harness.md
git -c commit.gpgsign=false commit -m "test: end-to-end smoke IT — bot joins server with plugin loaded"
```

---

## Phase 1 exit criteria

- `./gradlew test` runs fast and green, excluding integration tests (no network needed).
- `./gradlew integrationTest` boots a real Paper 1.21.4 server with the plugin, a headless
  MCProtocolLib bot logs in, and everything tears down cleanly — all asserted automatically.
- The harness (`PaperTestServer` + `BotClient`) exposes a reusable shape that P2 will extend
  to: connect a bot, record its POV, save a `.flashback`, and assert it with
  `FlashbackValidator`.
- `docs/test-harness.md` documents both suites.

## Known follow-ups (later phases)

- **P2** wires recording into this harness: after `bot.awaitJoin`, drive some movement,
  stop recording, then assert the produced file with `FlashbackValidator.validate(...)`.
- **Reference-file interop test** (from the P0 follow-ups): once a real `.flashback` exists,
  add a unit test that `FlashbackValidator` accepts it.
- If the integration suite proves too slow/flaky for routine runs, gate it behind a CI-only
  profile; keep the fast unit suite as the default developer loop.
