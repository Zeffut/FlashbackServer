# Phase 2a — Netty Packet Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture a real player's outbound PLAY packets, server-side, by injecting a handler
into their Netty pipeline — with zero external dependencies (no ProtocolLib) — and prove it
end-to-end with the P1 harness (a bot joins, we capture its outbound packets, assert we got a
plausible PLAY stream). This is part 1 of the recording core; P2b assembles the captured
stream into a `.flashback` using the P0 format module.

**Architecture:** A `capture` package. `PacketCapture` injects a `ChannelDuplexHandler` into a
player's Netty channel and forwards each outbound packet (as a `Packet<?>` object plus, where
useful, its raw encoded bytes) to a registered sink. A join/quit listener wires injection to
player lifecycle. Everything NMS-specific is isolated behind a small, version-tolerant
accessor discovered by Task 1's spike, so the rest of the codebase never touches internals.

**Tech Stack:** Java 21, Paper 1.21.5 (paperweight-userdev, Mojang-mapped), Netty (provided by
the server), JUnit 5 + the P1 harness for integration tests. No ProtocolLib.

---

## High-risk unknowns (why Task 1 is a spike)

We must empirically confirm, on a running Paper 1.21.5 server:
- how to obtain a player's Netty `Channel` from a Bukkit `Player` (NMS accessor / field names),
- the **handler names** in the outbound pipeline and the right place to observe packets,
- in what form an outbound message appears at our handler (a Minecraft `Packet<?>` object vs an
  already-encoded `ByteBuf`), since that decides how P2b will serialize to Flashback bytes,
- whether the plugin jar runs Mojang-mapped directly (Paper 1.20.5+) or needs reobf.

Task 1 answers these and writes them to `docs/netty-pipeline.md`. Do not build `PacketCapture`
(Task 2) until the spike documents a working channel accessor and injection point.

## File structure (created by this plan)

- `docs/netty-pipeline.md` — spike findings (accessor, handler names, packet form)
- `src/main/java/dev/zeffut/flashbackserver/capture/ChannelAccess.java` — Player → Netty Channel
- `src/main/java/dev/zeffut/flashbackserver/capture/CapturedPacket.java` — (Packet object, optional raw bytes)
- `src/main/java/dev/zeffut/flashbackserver/capture/PacketSink.java` — functional sink interface
- `src/main/java/dev/zeffut/flashbackserver/capture/PacketCapture.java` — inject/eject duplex handler
- `src/main/java/dev/zeffut/flashbackserver/capture/CaptureListener.java` — inject on join, eject on quit
- `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java` — register the listener (modify)
- `src/test/java/dev/zeffut/flashbackserver/capture/PacketCaptureIT.java` — end-to-end capture proof (`@Tag("integration")`)

---

### Task 1: Spike — discover the Netty pipeline on a running server

Write a throwaway probe inside the plugin that, when a player joins, locates their Netty
channel, logs the outbound handler names, injects a no-op observing handler, and logs the
runtime type of the first few outbound messages. Run it via the P1 harness, capture the log,
and document findings. The probe code is removed/replaced in Task 2; the DOC is the deliverable.

**Files:**
- Modify: `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java` (temporary probe)
- Create: `docs/netty-pipeline.md`

- [ ] **Step 1: Add a temporary join-probe to the plugin**

In `FlashbackServerPlugin.onEnable`, register a `PlayerJoinEvent` listener that runs the probe.
The probe must be tolerant: try the known Mojang-mapped path and fall back to reflection,
logging what it finds rather than throwing. Reference approach (adjust field names per what
compiles against the Mojang-mapped Paper API):
```java
// Pseudocode shape — confirm exact NMS types/fields at compile time:
// ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
// Connection conn = nmsPlayer.connection.connection;   // ServerGamePacketListenerImpl -> Connection
// io.netty.channel.Channel channel = conn.channel;
// channel.pipeline().names().forEach(n -> getLogger().info("[pipeline] " + n));
```
Then add a `ChannelDuplexHandler` whose `write(ctx, msg, promise)` logs
`msg.getClass().getName()` for the first ~10 messages, and calls `super.write(...)`.
If the Mojang field path does not compile or resolve at runtime, use reflection to walk the
`CraftPlayer` handle and find the `io.netty.channel.Channel` field, logging the path that works.

- [ ] **Step 2: Run the probe via the harness and capture the log**

Build and run a one-off integration check (you may temporarily add an `@Tag("integration")`
test that boots the server and connects a bot via the P1 harness, OR run `./gradlew runServer`
with a piped bot). Simplest: write a temporary test reusing `PaperTestServer` + `BotClient`,
let the bot join, sleep ~3s so the server sends play packets, then stop. Capture the server
log lines starting with `[pipeline]` and the logged message class names.

- [ ] **Step 3: Document findings in `docs/netty-pipeline.md`**

```markdown
# Paper 1.21.5 outbound Netty pipeline (observed)

## Channel accessor
<the exact code path from Player to io.netty.channel.Channel that worked>

## Outbound handler names (in order)
<the list printed by pipeline().names()>

## Form of outbound messages at a tail-injected duplex handler
<e.g. "messages arrive as net.minecraft.network.protocol.Packet objects before the encoder"
 OR "as io.netty.buffer.ByteBuf after the encoder named 'encoder'">

## Chosen injection point for capture
<which handler name to inject before/after, and whether we capture Packet objects or ByteBufs>

## Mapping note
<does the plugin run Mojang-mapped directly, or is reobf required?>
```

- [ ] **Step 4: Remove the probe, keep the doc, commit**

Revert `FlashbackServerPlugin` to its no-op state (remove the temporary probe/listener and any
temporary test). Confirm `./gradlew build` is green.
```bash
git add docs/netty-pipeline.md src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java
git -c commit.gpgsign=false commit -m "docs: document Paper 1.21.5 outbound Netty pipeline (capture spike)"
```

If the spike could not find a working channel accessor or injection point, STOP and report
BLOCKED with the captured log — P2 design must change.

---

### Task 2: `ChannelAccess` + `CapturedPacket` + `PacketSink` + `PacketCapture`

Implement the real capture, using the accessor and injection point documented in Task 1.

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/capture/ChannelAccess.java`
- Create: `src/main/java/dev/zeffut/flashbackserver/capture/CapturedPacket.java`
- Create: `src/main/java/dev/zeffut/flashbackserver/capture/PacketSink.java`
- Create: `src/main/java/dev/zeffut/flashbackserver/capture/PacketCapture.java`

- [ ] **Step 1: Write `CapturedPacket` and `PacketSink`**

```java
package dev.zeffut.flashbackserver.capture;

/** An outbound packet observed on a player's channel. {@code rawBytes} is the encoded
 *  id+payload when available at the chosen injection point, else null (P2b decides usage). */
public record CapturedPacket(String packetClass, byte[] rawBytes) {}
```
```java
package dev.zeffut.flashbackserver.capture;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface PacketSink {
    void accept(Player player, CapturedPacket packet);
}
```

- [ ] **Step 2: Write `ChannelAccess`** using the exact accessor documented in Task 1.

Encapsulate Player → `io.netty.channel.Channel`. Implement with the Mojang-mapped path the
spike confirmed (or the reflection path if that is what worked). It must throw a clear
`IllegalStateException` with the failing step if the internals are not as expected (fail-fast,
per the spec's R2 mitigation).
```java
package dev.zeffut.flashbackserver.capture;

import io.netty.channel.Channel;
import org.bukkit.entity.Player;

public final class ChannelAccess {
    private ChannelAccess() {}

    /** Returns the player's Netty channel. Throws IllegalStateException if internals differ. */
    public static Channel of(Player player) {
        // Implement using the path documented in docs/netty-pipeline.md (Task 1).
        // Wrap each dereference so the message identifies which step failed.
        ...
    }
}
```
(Fill `...` with the confirmed accessor. If using reflection, cache the resolved Field objects
in static finals resolved once.)

- [ ] **Step 3: Write `PacketCapture`** — inject/eject a duplex handler at the documented point.

```java
package dev.zeffut.flashbackserver.capture;

import io.netty.channel.*;
import org.bukkit.entity.Player;

public final class PacketCapture {
    private static final String HANDLER_NAME = "flashback_capture";

    private PacketCapture() {}

    /** Injects a capturing handler into the player's channel; forwards outbound packets to sink. */
    public static void inject(Player player, PacketSink sink) {
        Channel channel = ChannelAccess.of(player);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) return; // idempotent
            channel.pipeline().addBefore(<DOC_INJECTION_POINT>, HANDLER_NAME, new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    try {
                        sink.accept(player, CaptureSupport.toCaptured(msg));
                    } catch (Throwable t) {
                        // never break the connection because of capture
                    }
                    super.write(ctx, msg, promise);
                }
            });
        });
    }

    /** Removes the capturing handler if present. Safe to call on quit/disable. */
    public static void eject(Player player) {
        Channel channel;
        try { channel = ChannelAccess.of(player); } catch (RuntimeException e) { return; }
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) channel.pipeline().remove(HANDLER_NAME);
        });
    }
}
```
Replace `<DOC_INJECTION_POINT>` with the handler name from Task 1 (e.g. `"encoder"`), and
implement `CaptureSupport.toCaptured(Object msg)` inline or as a tiny private helper that turns
the observed message into a `CapturedPacket` (record the class name always; include rawBytes
only if the injection point exposes a `ByteBuf` — copy its readable bytes without consuming it
via `buf.copy()` / `ByteBufUtil.getBytes`). Keep it defensive: capture must never throw into
the pipeline.

- [ ] **Step 4: Compile** — `./gradlew compileJava`. Expected BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/zeffut/flashbackserver/capture/
git -c commit.gpgsign=false commit -m "feat: Netty packet capture (inject/eject duplex handler, zero-dep)"
```

---

### Task 3: `CaptureListener` — wire capture to player lifecycle

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/capture/CaptureListener.java`
- Modify: `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java`

- [ ] **Step 1: Write `CaptureListener`**

For this part of the core, the sink simply counts captured packets per player (a `ConcurrentHashMap<UUID, AtomicLong>`), exposing the count for the integration test. P2b replaces the
sink with the Flashback writer. Inject on `PlayerJoinEvent`, eject on `PlayerQuitEvent`.
```java
package dev.zeffut.flashbackserver.capture;

import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class CaptureListener implements Listener {
    private final ConcurrentHashMap<UUID, AtomicLong> counts = new ConcurrentHashMap<>();

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        AtomicLong counter = counts.computeIfAbsent(player.getUniqueId(), k -> new AtomicLong());
        PacketCapture.inject(player, (p, packet) -> counter.incrementAndGet());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PacketCapture.eject(event.getPlayer());
    }

    public long capturedCount(UUID playerId) {
        AtomicLong c = counts.get(playerId);
        return c == null ? 0 : c.get();
    }
}
```

- [ ] **Step 2: Register the listener in the plugin and expose it for tests**

Modify `FlashbackServerPlugin`:
```java
package dev.zeffut.flashbackserver;

import dev.zeffut.flashbackserver.capture.CaptureListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class FlashbackServerPlugin extends JavaPlugin {
    private final CaptureListener captureListener = new CaptureListener();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(captureListener, this);
        getLogger().info("FlashbackServer enabled.");
    }

    public CaptureListener captureListener() { return captureListener; }
}
```

- [ ] **Step 3: Build** — `./gradlew build`. Expected BUILD SUCCESSFUL, 14 unit tests still green.

- [ ] **Step 4: Commit**
```bash
git add src/main/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "feat: inject packet capture on join, eject on quit (packet counter sink)"
```

---

### Task 4: End-to-end capture proof (integration test)

Prove real packets are captured from a real bot via the harness.

**Files:**
- Create: `src/test/java/dev/zeffut/flashbackserver/capture/PacketCaptureIT.java`

- [ ] **Step 1: Write `PacketCaptureIT`**

Because the count lives in the server JVM (not the test JVM), assert via the server log: have
the capture sink log the count, then grep the harness-captured stdout. Simplest robust approach:
make the sink log a line like `"[capture] <name> packets=<n>"` periodically, OR assert on a
side-effect file the plugin writes. Use this approach: the test boots the server, the bot joins
and waits ~3s (server sends chunks/entity/keepalive packets), bot disconnects; the test asserts
the server log contained a capture line with a positive count.
```java
package dev.zeffut.flashbackserver.capture;

import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PacketCaptureIT {
    @Test
    void capturesOutboundPacketsForJoinedBot(@TempDir Path dir) throws Exception {
        int port = 25603;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "CaptureBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                Thread.sleep(3000); // let the server stream play packets to the bot
            }
            assertTrue(server.logContains("[capture] CaptureBot packets="),
                "no capture log line found");
            assertTrue(server.maxCapturedCount() > 0, "captured zero packets");
        }
    }
}
```
This requires two small harness additions (Step 2) so the test can observe the server-side count.

- [ ] **Step 2: Extend the harness to expose server log assertions**

Add to `PaperTestServer`: accumulate stdout lines into a thread-safe list, and add
`boolean logContains(String substring)` and `long maxCapturedCount()` (parse the integer after
`packets=` from any `[capture]` line). Have `CaptureListener` log `"[capture] <name> packets=<n>"`
on player quit (and/or on a repeating task). Keep the log line format stable — the test parses it.

- [ ] **Step 3: Run the integration test**

Run: `./gradlew integrationTest --tests '*PacketCaptureIT'`
Expected: PASS — the bot joins, the server captures a positive number of outbound packets,
clean teardown. If zero packets are captured, the injection point from Task 1 is wrong —
revisit the spike findings (do not weaken the assertion).

- [ ] **Step 4: Run full suites and commit**

Run: `./gradlew test integrationTest`
Expected: 14 unit tests PASS; all integration tests PASS (PaperTestServerIT, ServerSmokeIT, PacketCaptureIT).
```bash
git add src/test/java/dev/zeffut/flashbackserver/capture/PacketCaptureIT.java \
        src/main/java/dev/zeffut/flashbackserver/ src/test/java/dev/zeffut/flashbackserver/harness/
git -c commit.gpgsign=false commit -m "test: end-to-end proof that outbound packets are captured for a joined bot"
```

---

## Phase 2a exit criteria

- `docs/netty-pipeline.md` documents the real channel accessor, handler names, packet form, and
  chosen injection point on Paper 1.21.5.
- `PacketCapture.inject/eject` adds/removes a duplex handler on a player's channel, isolated
  behind `ChannelAccess`, defensive (never breaks the connection), zero external deps.
- An integration test proves a real bot's outbound packets are captured (count > 0), via the
  P1 harness, with clean teardown.
- `./gradlew test` stays fast/green; `./gradlew integrationTest` green.

## Known follow-ups (P2b and later)

- **P2b** swaps the counting sink for a Flashback-assembling sink: serialize captured packets
  to id+payload bytes, build the initial-state sequence from connection, write a `.flashback`
  via the P0 `FlashbackContainer`/`ChunkWriter`, and assert it with `FlashbackValidator`.
- **Serialization of `Packet<?>`**: if Task 1 finds messages arrive as Packet objects (not
  ByteBufs), P2b encodes them via the server's packet encoder for the PLAY protocol.
- **Folia** (P3): channel access + injection must run on the correct thread; the `eventLoop().execute`
  wrapping already keeps pipeline mutation on the Netty thread.
- **Initial-state correctness** is the major P2b risk (the spec's R3): a replay that opens in the
  real Flashback client needs the join/dimension/position/chunk packets at tick 0.
