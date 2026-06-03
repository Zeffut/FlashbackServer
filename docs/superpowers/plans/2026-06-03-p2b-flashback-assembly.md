# Phase 2b — Flashback Assembly Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a live capture session into a `.flashback` file: `/replay start players <p>` begins
recording a player's outbound PLAY packets (raw id+payload bytes), a server-tick hook emits a
next-tick action each tick, and `/replay stop players <p>` writes a `.flashback` via the P0
container. The verifiable milestone: a file produced from a real recording session **passes
`FlashbackValidator`** (the P0 oracle), asserted end-to-end through the P1 harness.

**Architecture:** A `record` package. `FlashbackRecorder` (one per recording) buffers `ReplayAction`s
on a writer thread (NEVER on the Netty event loop), fed by a capture sink that copies raw packet
bytes. A `RecordingManager` tracks active recordings. A `TickClock` (Bukkit scheduler) signals each
server tick so the recorder inserts a next-tick action. A `/replay` command drives start/stop. All
packet bytes come from capturing at the encoder's output (`addAfter("encoder")`), so no manual
Minecraft packet serialization is needed.

**Tech Stack:** Java 21, Paper 1.21.5 (paperweight-userdev), Netty, the P0 `format` module, the P1
harness for the integration test, JUnit 5. No ProtocolLib.

---

## High-risk unknowns (why Task 1 is a spike)

1. **Game-packet action format.** Flashback wraps each recorded packet in an *action* inside the
   chunk action stream. We know `flashback:action/next_tick` advances a tick (P0). We do NOT yet
   know the identifier + payload layout of the action that carries a clientbound game packet. This
   must be read from the Flashback source (clean-room: document the format, copy no code) and added
   to `docs/format/flashback-format.md`.
2. **Capture point for raw bytes.** P2a captures `Packet<?>` objects at `addBefore("encoder")`. For
   assembly we need id+payload BYTES. The spike doc says `addAfter("encoder")` yields the encoded,
   uncompressed, unframed ByteBuf. Confirm empirically: the bytes start with a parseable varint
   packet id and are not zlib-compressed at that point.

If either is blocked, STOP and report — the assembly design depends on both.

## Scope / honesty note

The milestone is **oracle-valid output**, not proven rendering in the real Flashback client. A
structurally valid file (correct magic, registry, snapshot block, per-tick next-tick actions,
consistent metadata) is what `FlashbackValidator` checks. Faithful initial-state for real-client
rendering (the spec's R3: login→play transition packets, which inject-on-join may miss) is tracked
as a follow-up and the P6 human spot-check — NOT required to complete P2b.

## File structure (created by this plan)

- `docs/format/flashback-format.md` — extended with the game-packet action format (Task 1)
- `src/main/java/dev/zeffut/flashbackserver/record/FlashbackRecorder.java` — buffers actions, writes the file
- `src/main/java/dev/zeffut/flashbackserver/record/RecordingManager.java` — active recordings registry
- `src/main/java/dev/zeffut/flashbackserver/record/TickClock.java` — per-tick signal via Bukkit scheduler
- `src/main/java/dev/zeffut/flashbackserver/command/ReplayCommand.java` — `/replay start|stop players`
- `src/main/resources/plugin.yml` — register the `replay` command (modify)
- `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java` — wire manager/clock/command (modify)
- `src/main/java/dev/zeffut/flashbackserver/capture/PacketCapture.java` — add a raw-bytes capture mode (modify)
- `src/test/java/dev/zeffut/flashbackserver/record/RecordingIT.java` — end-to-end: record → oracle-valid

---

### Task 1: Spike — game-packet action format + raw-bytes capture point

**Files:**
- Modify: `docs/format/flashback-format.md`
- (temporary probe; reverted before commit)

- [ ] **Step 1: Read the Flashback action classes (clean-room)**

Read these from the public Flashback source to learn the action that carries a game packet (do not
copy code — document the format in our words):
```
https://raw.githubusercontent.com/Moulberry/Flashback/master/src/main/java/com/moulberry/flashback/record/Recorder.java
https://api.github.com/repos/Moulberry/Flashback/git/trees/master?recursive=1   (to find action class paths)
```
Find the action types under `com/moulberry/flashback/action/` (e.g. names like `ActionNextTick`,
`ActionCreateLocalPlayer`, `ActionConfigurationPacket`, and the one that writes a normal clientbound
game packet). For the game-packet action, determine: its identifier string, and its payload layout
(e.g. does it write the raw encoded packet bytes directly, or a length + bytes, or a phase byte +
bytes?).

- [ ] **Step 2: Confirm the raw-bytes capture point empirically**

Add a temporary probe (mirroring the P2a spike) that, on player join, injects a duplex handler with
`addAfter("encoder", ...)` and logs, for the first ~5 outbound messages: the message runtime type,
and if it is an `io.netty.buffer.ByteBuf`, its `readableBytes()` and the first varint it contains
(read a varint from a duplicate of the buffer WITHOUT consuming the original). Run it via a temporary
`@Tag("integration")` test using `PaperTestServer` + `BotClient` (bot joins, sleep 2s, stop), and
capture the log. Confirm: messages at `addAfter("encoder")` are ByteBufs whose leading varint is a
plausible packet id, and note whether any are large enough that compression might apply.

- [ ] **Step 3: Document findings in `docs/format/flashback-format.md`**

Append a `## Game-packet action (for our writer)` section stating: the action identifier we will use
for a recorded game packet, the exact payload layout, and how a snapshot block sequences these. Also
append a `## Capture point` note: `addAfter("encoder")` yields uncompressed/unframed id+payload
ByteBufs (confirmed), with the observed example varint ids.

- [ ] **Step 4: Revert the probe, keep doc, commit**

Remove the temporary probe and test. `./gradlew build` green.
```bash
git add docs/format/flashback-format.md
git -c commit.gpgsign=false commit -m "docs: document Flashback game-packet action format + raw-bytes capture point (spike)"
```
If the action format or capture point cannot be determined, STOP and report BLOCKED with evidence.

---

### Task 2: Raw-bytes capture mode in `PacketCapture`

Add a capture variant that hands the encoded id+payload bytes to the sink (copying off the buffer so
the original is untouched).

**Files:**
- Modify: `src/main/java/dev/zeffut/flashbackserver/capture/PacketCapture.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/capture/PacketCaptureIT.java` (extend, optional assert)

- [ ] **Step 1: Add `injectRaw(Player, PacketSink)`**

Add a second injection method that installs the handler at the point Task 1 confirmed
(`addAfter("encoder")`), and for each outbound `io.netty.buffer.ByteBuf msg`, produces a
`CapturedPacket(msg.getClass().getName(), bytes)` where `bytes = io.netty.buffer.ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes())` (a copy that does NOT consume or mutate the original buffer). Non-ByteBuf messages (if any) are forwarded with null bytes. Keep the existing
`inject(...)` (Packet-object mode) as-is. Reuse a distinct handler name constant (e.g.
`"flashback_capture_raw"`) and keep the same defensive guarantees (event-loop mutation, try/catch,
missing-handler logged, idempotent).

- [ ] **Step 2: Build** — `./gradlew build` → BUILD SUCCESSFUL, 14 unit tests green.

- [ ] **Step 3: Commit**
```bash
git add src/main/java/dev/zeffut/flashbackserver/capture/PacketCapture.java
git -c commit.gpgsign=false commit -m "feat: raw-bytes packet capture mode (encoded id+payload via ByteBuf copy)"
```

---

### Task 3: `FlashbackRecorder` + `TickClock` + `RecordingManager`

Assemble captured bytes + per-tick markers into a `.flashback`, off the Netty thread.

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/record/FlashbackRecorder.java`
- Create: `src/main/java/dev/zeffut/flashbackserver/record/TickClock.java`
- Create: `src/main/java/dev/zeffut/flashbackserver/record/RecordingManager.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/record/FlashbackRecorderTest.java`

- [ ] **Step 1: Write the failing unit test for `FlashbackRecorder`**

`FlashbackRecorder` is unit-testable without a server: feed it raw packet byte arrays and tick
signals, stop it, and assert the produced file passes the P0 `FlashbackValidator` with the expected
tick count. The action identifiers come from Task 1 (`GAME_PACKET_ACTION`) and `flashback:action/next_tick`.
```java
package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FlashbackRecorderTest {
    @Test
    void producesOracleValidFile(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("rec.flashback");
        FlashbackRecorder recorder = new FlashbackRecorder(out, "TestPlayer", 769, 4189);
        recorder.onPacket(new byte[]{1, 2, 3});
        recorder.onTick();
        recorder.onPacket(new byte[]{4});
        recorder.onTick();
        recorder.stop();

        FlashbackValidator.Report report = FlashbackValidator.validate(out);
        assertTrue(report.valid(), report.problems().toString());
        assertEquals(2, report.totalTicks());
        assertEquals(1, report.chunkCount());
    }
}
```

- [ ] **Step 2: Run it to verify failure** — `./gradlew test --tests '*FlashbackRecorderTest'` → FAIL (class missing).

- [ ] **Step 3: Implement `FlashbackRecorder`**

It accumulates `ReplayAction`s: each `onPacket(byte[])` appends a game-packet action (identifier from
Task 1, payload = the raw bytes); each `onTick()` appends a `flashback:action/next_tick` action with
empty payload and increments a tick counter. `stop()` writes one chunk `c0.flashback` via
`ChunkWriter.write(snapshot, actions)` (snapshot = empty `new byte[0]` for the MVP), builds a
`FlashbackMeta` (name, protocolVersion, dataVersion, totalTicks = tick count, one ChunkMeta with
duration = tick count), and writes the container via `FlashbackContainer`. All `ReplayAction`
buffering happens under a lock or on a single-thread executor so it is safe to call `onPacket` from
the Netty thread and `onTick` from the main thread. Use the constant
`GAME_PACKET_ACTION` documented in Task 1.
```java
package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public final class FlashbackRecorder {
    // Identifier for a recorded clientbound game packet — value confirmed in Task 1.
    static final String GAME_PACKET_ACTION = "<from Task 1>";
    static final String NEXT_TICK_ACTION = "flashback:action/next_tick";

    private final Path output;
    private final String playerName;
    private final int protocolVersion;
    private final int dataVersion;
    private final List<ReplayAction> actions = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int ticks = 0;
    private boolean stopped = false;

    public FlashbackRecorder(Path output, String playerName, int protocolVersion, int dataVersion) {
        this.output = output;
        this.playerName = playerName;
        this.protocolVersion = protocolVersion;
        this.dataVersion = dataVersion;
    }

    public void onPacket(byte[] idAndPayload) {
        lock.lock();
        try {
            if (stopped) return;
            actions.add(new ReplayAction(GAME_PACKET_ACTION, idAndPayload));
        } finally { lock.unlock(); }
    }

    public void onTick() {
        lock.lock();
        try {
            if (stopped) return;
            actions.add(new ReplayAction(NEXT_TICK_ACTION, new byte[0]));
            ticks++;
        } finally { lock.unlock(); }
    }

    public void stop() throws Exception {
        List<ReplayAction> snapshotActions;
        int tickCount;
        lock.lock();
        try {
            if (stopped) return;
            stopped = true;
            snapshotActions = List.copyOf(actions);
            tickCount = ticks;
        } finally { lock.unlock(); }

        byte[] chunk = ChunkWriter.write(new byte[0], snapshotActions);
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

- [ ] **Step 4: Run the test** — `./gradlew test --tests '*FlashbackRecorderTest'` → PASS.

- [ ] **Step 5: Write `TickClock`** (per-tick signal)
```java
package dev.zeffut.flashbackserver.record;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.function.Consumer;

/** Fires a callback once per server tick on the main thread, until stopped. */
public final class TickClock {
    private final Plugin plugin;
    private BukkitTask task;

    public TickClock(Plugin plugin) { this.plugin = plugin; }

    public void start(Runnable onTick) {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, onTick, 1L, 1L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }
}
```

- [ ] **Step 6: Write `RecordingManager`** — tracks one recorder per player UUID; start wires
`PacketCapture.injectRaw(player, sink→recorder.onPacket(bytes))` and a `TickClock`; stop ejects,
stops the clock, calls `recorder.stop()`. Keep it small and thread-safe (`ConcurrentHashMap`).
```java
package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.capture.PacketCapture;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RecordingManager {
    private final Plugin plugin;
    private final Path outputDir;
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();

    private record Active(FlashbackRecorder recorder, TickClock clock) {}

    public RecordingManager(Plugin plugin, Path outputDir) {
        this.plugin = plugin;
        this.outputDir = outputDir;
    }

    public boolean start(Player player) {
        UUID id = player.getUniqueId();
        if (active.containsKey(id)) return false;
        Path out = outputDir.resolve(player.getName() + "-" + id + ".flashback");
        // protocol/data versions: use a placeholder pair for the MVP; refine when known.
        FlashbackRecorder recorder = new FlashbackRecorder(out, player.getName(), 769, 4189);
        TickClock clock = new TickClock(plugin);
        active.put(id, new Active(recorder, clock));
        PacketCapture.injectRaw(player, (p, packet) -> {
            if (packet.rawBytes() != null) recorder.onPacket(packet.rawBytes());
        });
        clock.start(recorder::onTick);
        return true;
    }

    public Path stop(Player player) throws Exception {
        Active a = active.remove(player.getUniqueId());
        if (a == null) return null;
        PacketCapture.eject(player);
        a.clock().stop();
        a.recorder().stop();
        return outputDir.resolve(player.getName() + "-" + player.getUniqueId() + ".flashback");
    }

    public boolean isRecording(Player player) { return active.containsKey(player.getUniqueId()); }
}
```

- [ ] **Step 7: Build** — `./gradlew build` → BUILD SUCCESSFUL, all unit tests green (incl. the new recorder test).

- [ ] **Step 8: Commit**
```bash
git add src/main/java/dev/zeffut/flashbackserver/record/ src/test/java/dev/zeffut/flashbackserver/record/
git -c commit.gpgsign=false commit -m "feat: FlashbackRecorder + TickClock + RecordingManager (assemble capture into .flashback)"
```

---

### Task 4: `/replay` command + plugin wiring + end-to-end IT

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/command/ReplayCommand.java`
- Modify: `src/main/resources/plugin.yml` (register `replay` command)
- Modify: `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/record/RecordingIT.java`

- [ ] **Step 1: Write `ReplayCommand`** handling `/replay start players <name>` and
`/replay stop players <name>`. Resolve the player by name; call `RecordingManager.start/stop`; reply
with the result (and the output path on stop). Keep it minimal and defensive (unknown player,
already-recording, not-recording messages).
```java
package dev.zeffut.flashbackserver.command;

import dev.zeffut.flashbackserver.record.RecordingManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class ReplayCommand implements CommandExecutor {
    private final RecordingManager manager;
    public ReplayCommand(RecordingManager manager) { this.manager = manager; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 3 || !args[1].equalsIgnoreCase("players")) {
            sender.sendMessage("Usage: /replay <start|stop> players <player>");
            return true;
        }
        Player target = sender.getServer().getPlayerExact(args[2]);
        if (target == null) { sender.sendMessage("Unknown player: " + args[2]); return true; }
        try {
            if (args[0].equalsIgnoreCase("start")) {
                sender.sendMessage(manager.start(target) ? "Recording " + target.getName()
                    : target.getName() + " is already being recorded");
            } else if (args[0].equalsIgnoreCase("stop")) {
                var path = manager.stop(target);
                sender.sendMessage(path != null ? "Saved replay: " + path : target.getName() + " was not being recorded");
            } else {
                sender.sendMessage("Usage: /replay <start|stop> players <player>");
            }
        } catch (Exception e) {
            sender.sendMessage("Replay error: " + e.getMessage());
        }
        return true;
    }
}
```

- [ ] **Step 2: Register the command in `plugin.yml`** — add:
```yaml
commands:
  replay:
    description: Control server-side Flashback recordings.
    usage: /replay <start|stop> players <player>
```

- [ ] **Step 3: Wire it in `FlashbackServerPlugin.onEnable`**

Create a `RecordingManager` writing to `getDataFolder()/replays`, and set the command executor:
```java
java.nio.file.Path replays = getDataFolder().toPath().resolve("replays");
java.nio.file.Files.createDirectories(replays);
RecordingManager manager = new RecordingManager(this, replays);
getCommand("replay").setExecutor(new dev.zeffut.flashbackserver.command.ReplayCommand(manager));
```
(Keep the existing `CaptureListener` registration from P2a or remove it if it conflicts — the
counting listener is no longer needed for P2b; you may leave it or drop it, but if you drop it also
drop its `[capture]` log usage. Prefer leaving P2a's listener untouched to avoid scope creep, OR
remove it cleanly if it double-injects. Decide and note it.)

- [ ] **Step 4: Write the end-to-end IT** `src/test/java/dev/zeffut/flashbackserver/record/RecordingIT.java`

The bot joins; the test issues `/replay start players CaptureBot` via the server console, waits a few
seconds while the bot stays connected (server streams packets + ticks accrue), issues
`/replay stop players CaptureBot`, then locates the produced `.flashback` under the server's
`plugins/FlashbackServer/replays/` and asserts `FlashbackValidator.validate(...)` accepts it with a
positive tick count. This needs a harness helper to send a console command and to resolve the run
directory. Add to `PaperTestServer`: `void sendCommand(String command)` (writes `command + "\n"` to
the server process stdin) and expose `runDir()` (already present). Use the existing `awaitLogLine`
to synchronize on the command's reply lines (e.g. await `"Recording CaptureBot"` then
`"Saved replay:"`).
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
class RecordingIT {
    @Test
    void recordsAnOracleValidReplay(@TempDir Path dir) throws Exception {
        int port = 25604;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "CaptureBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                server.sendCommand("replay start players CaptureBot");
                assertTrue(server.awaitLogLine("Recording CaptureBot", 15), "start not confirmed");
                Thread.sleep(4000); // accrue ticks + packets
                server.sendCommand("replay stop players CaptureBot");
                assertTrue(server.awaitLogLine("Saved replay:", 15), "stop not confirmed");
            }
            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            try (Stream<Path> files = Files.list(replays)) {
                Path replay = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no .flashback produced in " + replays));
                FlashbackValidator.Report report = FlashbackValidator.validate(replay);
                assertTrue(report.valid(), report.problems().toString());
                assertTrue(report.totalTicks() > 0, "recording had zero ticks");
            }
        }
    }
}
```

- [ ] **Step 5: Run the IT** — `./gradlew integrationTest --tests '*RecordingIT'` → PASS (a real
recording is produced and the oracle accepts it). If the oracle rejects it, READ the problems and fix
the writer/format (do not weaken the assertion). Common causes: tick action identifier mismatch,
metadata tick count not matching the next-tick action count.

- [ ] **Step 6: Run full suites and commit**

Run: `./gradlew test integrationTest` → all unit tests + all ITs green.
```bash
git add src/main/java/dev/zeffut/flashbackserver/ src/main/resources/plugin.yml \
        src/test/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "feat: /replay start|stop players produces an oracle-valid .flashback (end-to-end)"
```

---

## Phase 2b exit criteria

- The game-packet action format and the raw-bytes capture point are documented (Task 1).
- `FlashbackRecorder` assembles captured packet bytes + per-tick markers into a `.flashback`, with
  buffering safe across the Netty and main threads, never blocking the event loop.
- `/replay start players <p>` ... `/replay stop players <p>` produces a `.flashback` on disk.
- An integration test proves a real recording session yields a file that `FlashbackValidator`
  accepts with a positive tick count, via the P1 harness.
- `./gradlew test` fast/green; `./gradlew integrationTest` green.

## Known follow-ups (later phases)

- **Renderable initial state (R3):** capture from the login→play transition (inject earlier than
  `PlayerJoinEvent`) and write a real snapshot block so the file opens in the actual Flashback client.
  This + a reference-file interop test + the P6 human spot-check prove true client compatibility.
- **Chunk rollover** at 6000 ticks (P0 doc) — the MVP writes a single chunk; add rollover later.
- **Correct protocol/data versions** in metadata (read from the server rather than the placeholder pair).
- **P3 (Folia)** + **P4 (clips)** build on this recorder.
