# Phase 4 — Clip System (signature feature) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The headline differentiator — "save the last N seconds" clips. A bounded ring buffer keeps a
player's recent outbound packets; on a trigger (a command or the player's death) it writes a short
`.flashback` clip. Proven end-to-end: a bot is armed, plays for a while, a clip is triggered, and the
produced clip is oracle-valid with a tick count bounded by the configured window (not the whole session).

**Architecture:** A `clip` package. `ClipBuffer` stores recent packets as per-tick frames in a deque and
evicts frames older than the window — pure, unit-testable. `ClipManager` arms a buffer per player (raw
capture sink + per-entity `TickClock` feeding it), and `saveClip` writes the buffer's current contents
to a `.flashback` off-thread (`CompletableFuture<Path>`), reusing a shared `ReplayFiles.write` helper
also adopted by `FlashbackRecorder`. Triggers: the `/replay clip` subcommand and a `PlayerDeathEvent`
listener (config-toggled). Scheduling stays on `PlatformScheduler` (Folia-safe).

**Tech Stack:** Java 21, Paper/Folia 1.21.5, the P0 format module, P2 capture/recorder, P3
`PlatformScheduler`, the P1 harness, JUnit 5. No ProtocolLib.

## Scope / honesty note
Like P2b, a clip is written with an empty initial-state snapshot, so it is **oracle-valid** but not yet
guaranteed to render in the real Flashback client (the deferred R3 work). That's acceptable for this
phase; renderable clips come with R3 + the P6 spot-check.

## File structure
- `src/main/java/dev/zeffut/flashbackserver/record/ReplayFiles.java` — shared `.flashback` write helper (new)
- `src/main/java/dev/zeffut/flashbackserver/record/FlashbackRecorder.java` — use `ReplayFiles.write` (modify)
- `src/main/java/dev/zeffut/flashbackserver/clip/ClipBuffer.java` — bounded per-tick ring buffer (new)
- `src/main/java/dev/zeffut/flashbackserver/clip/ClipManager.java` — arm/disarm + saveClip (new)
- `src/main/java/dev/zeffut/flashbackserver/clip/ClipDeathListener.java` — auto-clip on death (new)
- `src/main/java/dev/zeffut/flashbackserver/command/ReplayCommand.java` — add `clip` subcommands (modify)
- `src/main/resources/plugin.yml` — (commands already declared; no change unless needed)
- `src/main/resources/config.yml` — clip window + auto-clip toggle (new)
- `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java` — wire ClipManager + listener + config (modify)
- `src/test/java/dev/zeffut/flashbackserver/clip/ClipBufferTest.java` — eviction/snapshot (new)
- `src/test/java/dev/zeffut/flashbackserver/clip/ClipIT.java` — end-to-end clip (new)

---

### Task 1: `ReplayFiles` shared writer + `ClipBuffer` (TDD)

**Files:**
- Create `src/main/java/dev/zeffut/flashbackserver/record/ReplayFiles.java`
- Modify `src/main/java/dev/zeffut/flashbackserver/record/FlashbackRecorder.java`
- Create `src/main/java/dev/zeffut/flashbackserver/clip/ClipBuffer.java`
- Test `src/test/java/dev/zeffut/flashbackserver/clip/ClipBufferTest.java`

- [ ] **Step 1: Extract `ReplayFiles.write`** (DRY — used by recorder and clips)
```java
package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.*;
import java.nio.file.Path;
import java.util.List;

public final class ReplayFiles {
    private ReplayFiles() {}

    /** Writes a single-chunk .flashback from the given actions. tickCount = number of next_tick actions. */
    public static void write(Path output, String playerName, int protocolVersion, int dataVersion,
                             List<ReplayAction> actions, int tickCount) throws Exception {
        byte[] chunk = ChunkWriter.write(new byte[0], actions);
        FlashbackMeta meta = new FlashbackMeta();
        meta.name = playerName;
        meta.versionString = "1.21.5";
        meta.protocolVersion = protocolVersion;
        meta.dataVersion = dataVersion;
        meta.totalTicks = tickCount;
        meta.chunks.put("c0.flashback", new ChunkMeta(tickCount));
        try (var writer = FlashbackContainer.create(output)) {
            writer.writeMetadata(meta);
            writer.writeChunk("c0.flashback", chunk);
        }
    }
}
```

- [ ] **Step 2: Make `FlashbackRecorder.stop` use `ReplayFiles.write`** — replace the inline
ChunkWriter/FlashbackMeta/FlashbackContainer block in `stop()` with a single
`ReplayFiles.write(output, playerName, protocolVersion, dataVersion, snapshotActions, tickCount);`
(keep the lock-snapshot logic and the `stopped` guard exactly as-is). Run
`./gradlew test --tests '*FlashbackRecorderTest'` → still PASS (behavior unchanged).

- [ ] **Step 3: Write the failing `ClipBufferTest`**
```java
package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.ReplayAction;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClipBufferTest {
    private long nextTicks(List<ReplayAction> a) {
        return a.stream().filter(x -> x.identifier().equals("flashback:action/next_tick")).count();
    }

    @Test
    void retainsOnlyTheLastWindowOfTicks() {
        // window = 2 ticks. Feed 5 ticks, each with one packet; only the last 2 frames survive.
        ClipBuffer buffer = new ClipBuffer(2);
        for (int i = 0; i < 5; i++) {
            buffer.onPacket(new byte[]{(byte) i});
            buffer.onTick();
        }
        List<ReplayAction> clip = buffer.snapshotClip();
        assertEquals(2, nextTicks(clip), "should retain exactly the window's worth of ticks");
        // last two packets are bytes 3 and 4 (frames for ticks 3 and 4)
        long gamePackets = clip.stream().filter(x -> x.identifier().equals("flashback:action/game_packet")).count();
        assertEquals(2, gamePackets);
        assertEquals(2, buffer.tickCount());
    }

    @Test
    void emptyBufferYieldsNoActions() {
        ClipBuffer buffer = new ClipBuffer(5);
        assertEquals(0, buffer.snapshotClip().size());
        assertEquals(0, buffer.tickCount());
    }

    @Test
    void packetsInTheCurrentUnfinishedTickAreIncluded() {
        ClipBuffer buffer = new ClipBuffer(5);
        buffer.onTick();                       // frame 0 (empty)
        buffer.onPacket(new byte[]{9});        // belongs to the in-progress frame after tick 0
        List<ReplayAction> clip = buffer.snapshotClip();
        // 1 completed tick + the trailing packet should appear
        assertEquals(1, nextTicks(clip));
        assertTrue(clip.stream().anyMatch(x -> x.identifier().equals("flashback:action/game_packet")));
    }
}
```

- [ ] **Step 4: Run it to verify failure** — `./gradlew test --tests '*ClipBufferTest'` → FAIL (class missing).

- [ ] **Step 5: Implement `ClipBuffer`** — a deque of per-tick frames, evicting frames beyond the window.
```java
package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.ReplayAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Thread-safe bounded ring buffer of recent outbound packets, grouped into per-tick frames. */
public final class ClipBuffer {
    static final String GAME_PACKET_ACTION = "flashback:action/game_packet";
    static final String NEXT_TICK_ACTION = "flashback:action/next_tick";

    private final int windowTicks;
    private final Deque<List<byte[]>> frames = new ArrayDeque<>(); // completed frames, oldest first
    private List<byte[]> current = new ArrayList<>();              // in-progress (current tick) packets
    private final ReentrantLock lock = new ReentrantLock();

    /** @param windowSeconds clip length; internally windowSeconds * 20 ticks. */
    public ClipBuffer(int windowSeconds) { this.windowTicks = Math.max(1, windowSeconds) * 20; }

    // test-friendly: allow constructing directly in ticks via a factory in the test? keep seconds API.

    public void onPacket(byte[] idAndPayload) {
        lock.lock();
        try { current.add(idAndPayload); } finally { lock.unlock(); }
    }

    /** Finalizes the current tick frame and evicts frames older than the window. */
    public void onTick() {
        lock.lock();
        try {
            frames.addLast(current);
            current = new ArrayList<>();
            while (frames.size() > windowTicks) frames.removeFirst();
        } finally { lock.unlock(); }
    }

    /** Number of completed ticks currently retained (≤ window). */
    public int tickCount() {
        lock.lock();
        try { return frames.size(); } finally { lock.unlock(); }
    }

    /** Snapshot of the retained window as a writable action list: per frame, its packets then a next_tick;
     *  finally any packets in the in-progress frame. */
    public List<ReplayAction> snapshotClip() {
        lock.lock();
        try {
            List<ReplayAction> actions = new ArrayList<>();
            for (List<byte[]> frame : frames) {
                for (byte[] p : frame) actions.add(new ReplayAction(GAME_PACKET_ACTION, p));
                actions.add(new ReplayAction(NEXT_TICK_ACTION, new byte[0]));
            }
            for (byte[] p : current) actions.add(new ReplayAction(GAME_PACKET_ACTION, p));
            return actions;
        } finally { lock.unlock(); }
    }
}
```
NOTE: the test constructs `new ClipBuffer(2)` expecting a 2-TICK window, but the constructor takes
SECONDS (×20). Reconcile: change the test to use a package-private tick-based factory, OR make the
test window match. Implement a package-private static factory `ClipBuffer.ofTicks(int windowTicks)`
for tests and keep the public `ClipBuffer(int windowSeconds)` for production; update the test to use
`ClipBuffer.ofTicks(2)`. (Add the factory + a private all-args constructor.) Keep behavior identical.

- [ ] **Step 6: Run tests** — `./gradlew test --tests '*ClipBufferTest'` → PASS. Then `./gradlew build` green.

- [ ] **Step 7: Commit**
```bash
git add src/main/java/dev/zeffut/flashbackserver/record/ReplayFiles.java \
        src/main/java/dev/zeffut/flashbackserver/record/FlashbackRecorder.java \
        src/main/java/dev/zeffut/flashbackserver/clip/ClipBuffer.java \
        src/test/java/dev/zeffut/flashbackserver/clip/ClipBufferTest.java
git -c commit.gpgsign=false commit -m "feat: ClipBuffer ring buffer + shared ReplayFiles writer (TDD)"
```

---

### Task 2: `ClipManager` — arm/disarm + saveClip

**Files:** Create `src/main/java/dev/zeffut/flashbackserver/clip/ClipManager.java`.

- [ ] **Step 1: Implement `ClipManager`**

Arms a `ClipBuffer` per player (raw capture sink feeding `onPacket`, a per-entity `TickClock` feeding
`onTick`), and `saveClip` writes the current buffer to a `.flashback` off-thread.
```java
package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.capture.PacketCapture;
import dev.zeffut.flashbackserver.platform.PlatformScheduler;
import dev.zeffut.flashbackserver.record.ReplayFiles;
import dev.zeffut.flashbackserver.record.TickClock;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClipManager {
    private final Plugin plugin;
    private final Path outputDir;
    private final int windowSeconds;
    private final ConcurrentHashMap<UUID, Armed> armed = new ConcurrentHashMap<>();
    private final AtomicInteger clipCounter = new AtomicInteger();

    private record Armed(ClipBuffer buffer, TickClock clock) {}

    public ClipManager(Plugin plugin, Path outputDir, int windowSeconds) {
        this.plugin = plugin; this.outputDir = outputDir; this.windowSeconds = windowSeconds;
    }

    public boolean arm(Player player) {
        UUID id = player.getUniqueId();
        if (armed.containsKey(id)) return false;
        ClipBuffer buffer = new ClipBuffer(windowSeconds);
        TickClock clock = new TickClock(plugin, player);
        armed.put(id, new Armed(buffer, clock));
        PacketCapture.injectRaw(player, (p, packet) -> {
            if (packet.rawBytes() != null) buffer.onPacket(packet.rawBytes());
        });
        clock.start(buffer::onTick);
        return true;
    }

    public boolean disarm(Player player) {
        Armed a = armed.remove(player.getUniqueId());
        if (a == null) return false;
        PacketCapture.ejectRaw(player);
        a.clock().stop();
        return true;
    }

    public boolean isArmed(Player player) { return armed.containsKey(player.getUniqueId()); }

    /** Writes the player's current clip window to disk async. Returns a future of the path (null if not armed). */
    public CompletableFuture<Path> saveClip(Player player) {
        Armed a = armed.get(player.getUniqueId());
        CompletableFuture<Path> future = new CompletableFuture<>();
        if (a == null) { future.complete(null); return future; }
        List<dev.zeffut.flashbackserver.format.ReplayAction> actions = a.buffer().snapshotClip();
        int ticks = a.buffer().tickCount();
        String name = player.getName();
        Path out = outputDir.resolve(name + "-clip-" + clipCounter.incrementAndGet() + ".flashback");
        PlatformScheduler.async(plugin, () -> {
            try {
                ReplayFiles.write(out, name, 769, 4189, actions, ticks);
                plugin.getLogger().info("Saved clip: " + out);
                future.complete(out);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write clip for " + name + ": " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}
```

- [ ] **Step 2: Build** — `./gradlew build` → BUILD SUCCESSFUL, unit tests green. Commit.
```bash
git add src/main/java/dev/zeffut/flashbackserver/clip/ClipManager.java
git -c commit.gpgsign=false commit -m "feat: ClipManager (arm/disarm + async saveClip)"
```

---

### Task 3: triggers — `/replay clip` subcommand, death listener, config

**Files:** Modify `ReplayCommand`; create `ClipDeathListener`; create `config.yml`; modify `FlashbackServerPlugin`.

- [ ] **Step 1: Add `config.yml`** `src/main/resources/config.yml`:
```yaml
clips:
  # Length of the rolling clip window, in seconds.
  window-seconds: 30
  # Automatically save a clip when an armed player dies.
  auto-clip-on-death: true
```

- [ ] **Step 2: Extend `ReplayCommand`** to handle `clip` subcommands while keeping the existing
`start|stop players` behavior. Add handling for:
- `/replay clip arm <player>` → `clipManager.arm(target)` (message armed / already armed)
- `/replay clip disarm <player>` → `clipManager.disarm(target)`
- `/replay clip save <player>` → `clipManager.saveClip(target)` (message "Saving clip…"; the async log
  line `Saved clip: <path>` is the on-disk confirmation)
Inject the `ClipManager` into `ReplayCommand` (add it as a second constructor arg). Update the usage
string. Keep arg parsing defensive (unknown player, wrong arity). Concretely, restructure
`onCommand` to branch on `args[0]`: `start`/`stop` (existing, `players` form) vs `clip` (new, with
`arm`/`disarm`/`save` + player). Show the full handler in the implementation; do not leave it abstract.

- [ ] **Step 3: `ClipDeathListener`** `src/main/java/dev/zeffut/flashbackserver/clip/ClipDeathListener.java`:
```java
package dev.zeffut.flashbackserver.clip;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** Auto-saves a clip when an armed player dies (if enabled). */
public final class ClipDeathListener implements Listener {
    private final ClipManager clips;
    private final boolean enabled;

    public ClipDeathListener(ClipManager clips, boolean enabled) { this.clips = clips; this.enabled = enabled; }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled) return;
        var player = event.getEntity();
        if (clips.isArmed(player)) clips.saveClip(player);
    }
}
```
(`event.getEntity()` returns the `Player` for `PlayerDeathEvent`.)

- [ ] **Step 4: Wire it in `FlashbackServerPlugin.onEnable`** — load config, create the `ClipManager`
(output dir `getDataFolder()/clips`, window from config), register `ClipDeathListener` (enabled from
config), and pass the `ClipManager` to `ReplayCommand`:
```java
saveDefaultConfig();
int window = getConfig().getInt("clips.window-seconds", 30);
boolean autoClip = getConfig().getBoolean("clips.auto-clip-on-death", true);
java.nio.file.Path clipsDir = getDataFolder().toPath().resolve("clips");
java.nio.file.Files.createDirectories(clipsDir);
ClipManager clipManager = new ClipManager(this, clipsDir, window);
getServer().getPluginManager().registerEvents(new dev.zeffut.flashbackserver.clip.ClipDeathListener(clipManager, autoClip), this);
getCommand("replay").setExecutor(new dev.zeffut.flashbackserver.command.ReplayCommand(manager, clipManager));
```
(Keep the existing RecordingManager `manager`, its listener registration, and CaptureListener.)

- [ ] **Step 5: Build** — `./gradlew build` → BUILD SUCCESSFUL, unit tests green. Commit.
```bash
git add src/main/resources/config.yml src/main/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "feat: /replay clip arm|disarm|save + auto-clip-on-death + config"
```

---

### Task 4: end-to-end clip integration test

**Files:** Create `src/test/java/dev/zeffut/flashbackserver/clip/ClipIT.java`.

- [ ] **Step 1: Write `ClipIT`** — arm clips, let the bot accrue packets, save a clip, assert oracle-valid
and that the tick count is positive and bounded by the window.
```java
package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ClipIT {
    @Test
    void savesAnOracleValidClip(@TempDir Path dir) throws Exception {
        int port = 25606;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "ClipBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                server.sendCommand("replay clip arm ClipBot");
                assertTrue(server.awaitLogLine("Armed clips for ClipBot", 15), "arm not confirmed");
                Thread.sleep(4000); // accrue packets + ticks into the rolling buffer
                server.sendCommand("replay clip save ClipBot");
                assertTrue(server.awaitLogLine("Saved clip:", 20), "clip not written");
            }
            Path clips = server.runDir().resolve("plugins/FlashbackServer/clips");
            try (Stream<Path> files = Files.list(clips)) {
                Path clip = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no clip produced in " + clips));
                FlashbackValidator.Report report = FlashbackValidator.validate(clip);
                assertTrue(report.valid(), report.problems().toString());
                assertTrue(report.totalTicks() > 0, "clip had zero ticks");
                // window default 30s = 600 ticks; ~4s of recording stays well under that.
                assertTrue(report.totalTicks() <= 600, "clip exceeded the window: " + report.totalTicks());
            }
        }
    }
}
```
The arm reply must log a line containing `Armed clips for ClipBot` — make `ReplayCommand`'s arm branch
print exactly that (and the IT greps it). Adjust the command's messages to match, or adjust the grep
strings to whatever the command prints — keep them consistent.

- [ ] **Step 2: Run the clip IT** — `./gradlew integrationTest --tests '*ClipIT'` → PASS. If the clip is
empty or exceeds the window, inspect the buffer eviction / arm wiring (do not weaken assertions).

- [ ] **Step 3: Run full suites and commit**
Run: `./gradlew test integrationTest` → all unit tests + ALL integration tests green (now including ClipIT).
```bash
git add src/test/java/dev/zeffut/flashbackserver/clip/ClipIT.java src/main/java/dev/zeffut/flashbackserver/command/ReplayCommand.java
git -c commit.gpgsign=false commit -m "test: end-to-end — /replay clip save produces an oracle-valid, window-bounded clip"
```

---

## Phase 4 exit criteria
- `ClipBuffer` retains only the last window's worth of per-tick frames (unit-tested eviction).
- `/replay clip arm|disarm|save <player>` works; an armed player's death auto-saves a clip (config-toggled).
- A clip is written off-thread and is oracle-valid with a tick count bounded by the configured window,
  proven by `ClipIT`.
- `./gradlew test` fast/green; `./gradlew integrationTest` green (Paper + Folia + clips).

## Known follow-ups
- **R3 renderable initial state** — clips, like full recordings, currently use an empty snapshot; a
  rolling mid-session snapshot is needed for the real Flashback client to render a clip from any start
  point. This is the headline pre-publish item (+ reference-file test + P6 spot-check).
- **Kill-based trigger / richer predicates** (record only certain players, combat triggers) — P-later.
- **P5 telemetry**, **P6 publish**.
