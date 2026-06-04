# Phase 3 — Folia Compatibility + Async Write Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make recording work on **Folia** (Paper's regionized multithreaded fork) without breaking
Paper, and move the `.flashback` file write off the tick thread. The differentiator "Folia-ready" must
be real and proven: a Folia server boots with the plugin, `/replay start|stop` produces an
oracle-valid file, asserted end-to-end.

**Architecture:** Adopt the **regionized scheduler API** (`Entity.getScheduler()`,
`Bukkit.getAsyncScheduler()`), which Paper also implements — so one code path runs on both. A small
`PlatformScheduler` isolates those calls. `TickClock` ticks via the player's `EntityScheduler` (region
thread on Folia, main thread on Paper). `RecordingManager.stop` returns a `CompletableFuture<Path>`
and performs the file write on the async scheduler, logging `Saved replay: <path>` only when the file
is actually on disk.

**Tech Stack:** Java 21, Paper/Folia 1.21.5 (paperweight-userdev), regionized scheduler API, the P1
harness (generalized to also boot Folia), JUnit 5. No ProtocolLib.

---

## High-risk unknowns (Task 1 spike, GO/NO-GO)

- Can a **Folia 1.21.5** server boot headless in this environment (download via PaperMC API project
  `folia`)? Folia has stricter startup (it warns/refuses on some configs).
- Does the CURRENT plugin break on Folia? Expected: `TickClock` uses
  `getServer().getScheduler().runTaskTimer(...)` (the global BukkitScheduler), which throws
  `UnsupportedOperationException` on Folia when `/replay start` runs. Confirm, to motivate the change.

If Folia cannot boot here, STOP and report BLOCKED — we cannot autonomously verify Folia and must
decide whether to claim Folia support without an automated test.

## File structure

- `docs/folia.md` — spike findings + how Folia testing works
- `src/test/java/dev/zeffut/flashbackserver/harness/PaperDownloader.java` — generalize to any PaperMC project (modify)
- `src/test/java/dev/zeffut/flashbackserver/harness/PaperTestServer.java` — boot a chosen project (paper|folia) (modify)
- `src/main/java/dev/zeffut/flashbackserver/platform/PlatformScheduler.java` — regionized scheduler wrapper (new)
- `src/main/java/dev/zeffut/flashbackserver/record/TickClock.java` — tick via the entity scheduler (modify)
- `src/main/java/dev/zeffut/flashbackserver/record/RecordingManager.java` — async write, CompletableFuture (modify)
- `src/main/java/dev/zeffut/flashbackserver/command/ReplayCommand.java` — handle the future (modify)
- `src/test/java/dev/zeffut/flashbackserver/record/FoliaRecordingIT.java` — Folia end-to-end (new)

---

### Task 1: Spike — boot Folia + confirm the current break (GO/NO-GO)

**Files:** Modify `PaperDownloader`/`PaperTestServer` minimally to allow a project arg; create `docs/folia.md`.

- [ ] **Step 1: Generalize the downloader to accept a project**

In `PaperDownloader`, change `resolve(Path targetDir)` to `resolve(Path targetDir, String project, String version)` that downloads from
`https://api.papermc.io/v2/projects/<project>/versions/<version>/builds` (same JSON shape: builds
array → last build → downloads.application.name). Keep a convenience `resolve(Path)` delegating to
`resolve(targetDir, "paper", "1.21.5")` so existing callers are unaffected. The cached file name
should include the project (e.g. `<project>.jar`) so paper and folia don't collide.

- [ ] **Step 2: Let `PaperTestServer` boot a chosen project**

Add an overload `start(Path baseDir, int port, String project)` (existing `start(baseDir, port)` calls
it with `"paper"`). It resolves the jar via `PaperDownloader.resolve(testServerDir, project, "1.21.5")`.
Folia may need extra startup flags or config; if Folia refuses to start with the current
server.properties, add what it needs (e.g. it tolerates the same offline + flat config). Keep the
"Done (" readiness detection.

- [ ] **Step 3: Spike test — boot Folia and try recording**

Write a TEMPORARY `@Tag("integration")` test `FoliaSpikeIT` that boots `PaperTestServer.start(dir, 25630, "folia")`, connects a `BotClient` (awaitJoin 60), sends `replay start players SpikeBot`, sleeps 3s, sends `replay stop players SpikeBot`, and just logs what happens (do NOT assert success yet). Run:
`./gradlew integrationTest --tests '*FoliaSpikeIT' 2>&1 | tee spike-folia.log`
Observe: did Folia reach `Done (`? When `/replay start` ran, did the server log an
`UnsupportedOperationException` (or similar) from the global scheduler? Capture the evidence.

- [ ] **Step 4: Document findings + verdict in `docs/folia.md`**

State: whether Folia boots here and cold-boot time; the exact failure the current plugin hits on
Folia (quote the stack/log); and the plan (adopt the entity/async scheduler API). If Folia booted,
this is GO.

- [ ] **Step 5: Remove the spike test, keep harness changes + doc, commit**

Delete `FoliaSpikeIT`. `gitignore` `spike-folia.log`. Keep the generalized downloader + server start
(they're needed by Task 4). `./gradlew build` green; existing ITs still compile and `./gradlew test`
green.
```bash
echo "spike-folia.log" >> .gitignore
git add docs/folia.md .gitignore src/test/java/dev/zeffut/flashbackserver/harness/
git -c commit.gpgsign=false commit -m "test: generalize harness to boot Folia; document Folia spike (current plugin breaks on global scheduler)"
```
If Folia did not boot, STOP and report BLOCKED with `spike-folia.log` evidence.

---

### Task 2: `PlatformScheduler` — regionized scheduler wrapper

**Files:** Create `src/main/java/dev/zeffut/flashbackserver/platform/PlatformScheduler.java`; test
`src/test/java/dev/zeffut/flashbackserver/platform/PlatformSchedulerTest.java` (compile-only smoke).

- [ ] **Step 1: Write `PlatformScheduler`**

Wrap the regionized API (works on Paper and Folia). Return a `Runnable` cancel-handle to avoid
exposing the platform task type.
```java
package dev.zeffut.flashbackserver.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

public final class PlatformScheduler {
    private PlatformScheduler() {}

    /** Runs {@code task} every {@code periodTicks} ticks on the entity's region thread (Folia) or
     *  the main thread (Paper). Returns a handle that cancels the repeating task. */
    public static Runnable repeatForEntity(Plugin plugin, Entity entity, long periodTicks, Runnable task) {
        ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(
            plugin, t -> task.run(), null, 1L, periodTicks);
        return () -> { if (scheduled != null) scheduled.cancel(); };
    }

    /** Runs {@code task} once, off the server threads (async). */
    public static void async(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, (Consumer<ScheduledTask>) t -> task.run());
    }
}
```
Note: `entity.getScheduler().runAtFixedRate` returns null if the entity is already removed/retired —
the cancel handle guards for null.

- [ ] **Step 2: Compile-smoke test** `PlatformSchedulerTest` — just asserts the class exists and the
methods are accessible (no server). A trivial test that references `PlatformScheduler.class` and the
method signatures via reflection or a no-op compile reference. Keep it minimal:
```java
package dev.zeffut.flashbackserver.platform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformSchedulerTest {
    @Test
    void hasExpectedApi() throws Exception {
        assertNotNull(PlatformScheduler.class.getMethod(
            "repeatForEntity", org.bukkit.plugin.Plugin.class, org.bukkit.entity.Entity.class,
            long.class, Runnable.class));
        assertNotNull(PlatformScheduler.class.getMethod(
            "async", org.bukkit.plugin.Plugin.class, Runnable.class));
    }
}
```

- [ ] **Step 3: Build + commit**
```bash
./gradlew build
git add src/main/java/dev/zeffut/flashbackserver/platform/ src/test/java/dev/zeffut/flashbackserver/platform/
git -c commit.gpgsign=false commit -m "feat: PlatformScheduler wrapping the regionized scheduler API (Paper + Folia)"
```

---

### Task 3: Tick via entity scheduler + async file write

**Files:** Modify `TickClock`, `RecordingManager`, `ReplayCommand`. Update `FlashbackRecorderTest`
expectations if needed (the recorder itself is unchanged).

- [ ] **Step 1: Rewrite `TickClock` to tick per-entity**

```java
package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.platform.PlatformScheduler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Fires a callback once per server tick tied to a player's region (Folia-safe). */
public final class TickClock {
    private final Plugin plugin;
    private final Player player;
    private Runnable cancel;

    public TickClock(Plugin plugin, Player player) { this.plugin = plugin; this.player = player; }

    public void start(Runnable onTick) {
        cancel = PlatformScheduler.repeatForEntity(plugin, player, 1L, onTick);
    }

    public void stop() { if (cancel != null) { cancel.run(); cancel = null; } }
}
```

- [ ] **Step 2: Make `RecordingManager.stop` async, returning a future**

`start` constructs `new TickClock(plugin, player)`. `stop` synchronously removes the entry, ejects the
raw handler, and stops the clock (all fast), then performs the recorder's file write on the async
scheduler, completing a `CompletableFuture<Path>` and logging `Saved replay: <path>` on success.
```java
// in RecordingManager:
public java.util.concurrent.CompletableFuture<Path> stop(Player player) {
    Active a = active.remove(player.getUniqueId());
    var future = new java.util.concurrent.CompletableFuture<Path>();
    if (a == null) { future.complete(null); return future; }
    PacketCapture.ejectRaw(player);
    a.clock().stop();
    dev.zeffut.flashbackserver.platform.PlatformScheduler.async(plugin, () -> {
        try {
            a.recorder().stop();             // the file write, off the server threads
            plugin.getLogger().info("Saved replay: " + a.output());
            future.complete(a.output());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to write replay for " + player.getName() + ": " + e.getMessage());
            future.completeExceptionally(e);
        }
    });
    return future;
}
```
Keep `start` and the `Active` record (recorder, clock, output) and the `PlayerQuitEvent` handler (it
now ignores the returned future). Update the quit handler to call `stop(player)` (fire-and-forget).
Ensure `start` uses the new `TickClock(plugin, player)` constructor.

- [ ] **Step 3: Update `ReplayCommand` for the future**

For `stop`, reply immediately that writing started, and log the saved path on completion (the async
log line `Saved replay:` is what the IT greps):
```java
} else if (args[0].equalsIgnoreCase("stop")) {
    if (!manager.isRecording(target)) { sender.sendMessage(target.getName() + " was not being recorded"); return true; }
    sender.sendMessage("Stopping recording for " + target.getName() + " (writing replay…)");
    manager.stop(target); // async; logs "Saved replay: <path>" when the file is on disk
}
```

- [ ] **Step 4: Build + run unit tests**

`./gradlew build` → BUILD SUCCESSFUL. `FlashbackRecorderTest` still passes (the recorder is unchanged).
There is no unit test for the manager (it needs a server); Task 4 covers it. If `FlashbackRecorderTest`
references anything changed, fix the test to match.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "feat: Folia-safe per-entity tick + async replay write (CompletableFuture)"
```

---

### Task 4: Verify on BOTH Paper and Folia

The existing `RecordingIT` (Paper) must still pass; add the Folia equivalent.

**Files:** Create `src/test/java/dev/zeffut/flashbackserver/record/FoliaRecordingIT.java`.

- [ ] **Step 1: Confirm Paper still works**

Run: `./gradlew integrationTest --tests '*RecordingIT'`
Expected: PASS (Paper recording still produces an oracle-valid file; the async write logs `Saved replay:`
which the existing test awaits before listing files — confirm the test still passes; if the async timing
changed, the test already polls via `awaitLogLine("Saved replay:")` then lists, so the file exists by
the time the log line appears).

- [ ] **Step 2: Write `FoliaRecordingIT`** — identical flow on a Folia server:
```java
package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class FoliaRecordingIT {
    @Test
    void recordsAnOracleValidReplayOnFolia(@TempDir Path dir) throws Exception {
        int port = 25605;
        try (PaperTestServer server = PaperTestServer.start(dir, port, "folia")) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "FoliaBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join the Folia server");
                server.sendCommand("replay start players FoliaBot");
                assertTrue(server.awaitLogLine("Recording FoliaBot", 15), "start not confirmed");
                Thread.sleep(4000);
                server.sendCommand("replay stop players FoliaBot");
                assertTrue(server.awaitLogLine("Saved replay:", 20), "replay not written");
            }
            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            try (Stream<Path> files = Files.list(replays)) {
                Path replay = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no .flashback produced on Folia in " + replays));
                FlashbackValidator.Report report = FlashbackValidator.validate(replay);
                assertTrue(report.valid(), report.problems().toString());
                assertTrue(report.totalTicks() > 0, "recording had zero ticks on Folia");
            }
        }
    }
}
```

- [ ] **Step 3: Run the Folia IT**

Run: `./gradlew integrationTest --tests '*FoliaRecordingIT'`
Expected: PASS — Folia boots, the per-entity tick + async write work, the oracle accepts the file. If
it fails with a scheduler error, the entity/async adoption is incomplete — fix the offending call
(do not weaken the assertion).

- [ ] **Step 4: Run full suites and commit**

Run: `./gradlew test integrationTest`
Expected: all unit tests + ALL integration tests (Paper + Folia) green.
```bash
git add src/test/java/dev/zeffut/flashbackserver/record/FoliaRecordingIT.java
git -c commit.gpgsign=false commit -m "test: prove /replay produces an oracle-valid .flashback on Folia too"
```

---

## Phase 3 exit criteria

- A Folia 1.21.5 server boots with the plugin (documented in `docs/folia.md`).
- All scheduling uses the regionized API via `PlatformScheduler`; no global-scheduler calls remain in
  the recording path. The file write happens off the server threads.
- `/replay start|stop players` produces an oracle-valid `.flashback` on BOTH Paper and Folia, proven by
  `RecordingIT` and `FoliaRecordingIT`.
- `./gradlew test` fast/green; `./gradlew integrationTest` green (Paper + Folia).

## Known follow-ups (later phases)

- **R3 renderable initial state** still deferred (P-later + P6 spot-check).
- **P4 (clips):** ring buffer + triggers + mid-session snapshot build on this recorder/scheduler.
- **P5 telemetry**, **P6 publish**.
- If Folia ITs are slow, consider running the Folia IT only in CI / behind a flag, keeping `RecordingIT`
  (Paper) as the default integration check.
