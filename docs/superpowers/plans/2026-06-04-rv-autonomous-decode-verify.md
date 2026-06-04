# RV — Autonomous Decode-Replay Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Verify produced `.flashback` files AUTONOMOUSLY by decoding every packet through Minecraft's
real clientbound PLAY codec on a live server — the same protocol layer the Flashback client uses to read
a replay. A replay whose every packet decodes cleanly (correct protocol state, full byte consumption) is
proven client-decodable, catching any malformed synthesized/captured bytes. The final pixel render in a
GL client is the only residual manual step (genuinely not headless-automatable; documented).

**Architecture:** A plugin-side `verify` package (NMS, confined) decodes a replay's packets via
`GameProtocols.CLIENTBOUND_TEMPLATE.bind(decorator(registryAccess)).codec().decode(buf)`. A
`/replay verify <file>` command runs it and logs a parseable result. Integration tests record a bot
doing varied actions, then verify the produced recording AND a clip, asserting zero decode errors.

**Tech Stack:** Java 21, Paper 1.21.5, NMS clientbound codec (same recipe as R3a synthesis), the P1
harness, JUnit 5.

## Why this is the right autonomous check
- The Flashback client reads a replay by feeding each stored packet through the SAME clientbound protocol
  codec. Decoding our bytes with `CLIENTBOUND_TEMPLATE.codec().decode` is exactly that step.
- It must run INSIDE the server JVM (the test JVM has no live registries; chunk/biome packets need real
  `registryAccess`). Hence a `/replay verify` command, triggered and asserted via the harness.
- It catches: malformed synthesized snapshot packets, captured-byte corruption, wrong packet ids, partial
  reads — i.e. everything that would make the real client throw on load. It does NOT cover GL rendering.

## File structure
- `src/main/java/dev/zeffut/flashbackserver/verify/ReplayDecodeVerifier.java` — NMS decode of a .flashback
- `src/main/java/dev/zeffut/flashbackserver/command/ReplayCommand.java` — add `verify <file>` (modify)
- `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java` — (already wires ReplayCommand; pass plugin for logging) (modify if needed)
- `src/test/java/dev/zeffut/flashbackserver/verify/DecodeVerifyIT.java` — record bot → verify recording + clip
- `docs/research/r3-spike.md` — append a note on the decode-verify approach + the GL boundary (modify)

---

### Task 1: `ReplayDecodeVerifier` + `/replay verify` command

**Files:**
- Create `src/main/java/dev/zeffut/flashbackserver/verify/ReplayDecodeVerifier.java`
- Modify `src/main/java/dev/zeffut/flashbackserver/command/ReplayCommand.java`

- [ ] **Step 1: Write `ReplayDecodeVerifier`** (NMS confined to this package).
```java
package dev.zeffut.flashbackserver.verify;

// imports: format.FlashbackContainer, format.ChunkReader, format.ReplayAction,
// net.minecraft.network.protocol.game.GameProtocols, net.minecraft.network.RegistryFriendlyByteBuf,
// net.minecraft.network.protocol.game.ClientGamePacketListener, net.minecraft.network.protocol.Packet,
// net.minecraft.network.codec.StreamCodec, net.minecraft.network.ProtocolInfo,
// net.minecraft.core.RegistryAccess, io.netty.buffer.Unpooled, io.netty.buffer.ByteBuf,
// java.nio.file.Path, java.util.*

public final class ReplayDecodeVerifier {
    public record Result(int decoded, int errors, java.util.List<String> problems) {
        public boolean ok() { return errors == 0 && decoded > 0; }
    }

    private ReplayDecodeVerifier() {}

    /**
     * Decodes every PLAY game-packet action (snapshot + stream) of the first chunk through the real
     * clientbound codec. The synthetic flashback:action/create_local_player and next_tick are skipped
     * (not wire packets). Returns counts + problems. Must be called where {@code registryAccess} is live.
     */
    public static Result verify(Path file, RegistryAccess registryAccess) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        int decoded = 0, errors = 0;
        ProtocolInfo<ClientGamePacketListener> info =
            GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess));
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = info.codec();

        try (var reader = FlashbackContainer.open(file)) {
            var meta = reader.readMetadata();
            for (String chunkName : meta.chunks.keySet()) {
                ChunkReader.Result chunk = ChunkReader.read(reader.readChunk(chunkName));
                java.util.List<ReplayAction> all = new java.util.ArrayList<>();
                all.addAll(chunk.snapshotActions());
                all.addAll(chunk.streamActions());
                for (ReplayAction a : all) {
                    if (!a.identifier().equals("flashback:action/game_packet")) continue; // skip synthetic/next_tick
                    ByteBuf buf = Unpooled.wrappedBuffer(a.payload());
                    try {
                        Packet<? super ClientGamePacketListener> packet = codec.decode(buf);
                        if (packet == null) { errors++; problems.add("null packet decoded"); }
                        else decoded++;
                        if (buf.isReadable()) {
                            errors++;
                            problems.add(packet.getClass().getSimpleName() + ": " + buf.readableBytes() + " trailing bytes");
                        }
                    } catch (Exception e) {
                        errors++;
                        if (problems.size() < 20) problems.add("decode failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
                    } finally {
                        buf.release();
                    }
                }
            }
        } catch (Exception e) {
            errors++;
            problems.add("container/chunk read failed: " + e.getMessage());
        }
        return new Result(decoded, errors, problems);
    }
}
```
(If a method/type signature differs from the R3a spike, adjust to what compiles — same recipe as
`snapshot/PacketSerializer` but `decode` instead of `encode`. Confine ALL NMS to this package.)

- [ ] **Step 2: Add `/replay verify <file>` to `ReplayCommand`.**
Add a `verify` branch: `args` = `["verify", "<filename>"]` (the file is resolved under the plugin's
recordings dir AND clips dir — try both). Run the verifier with the server's registry access
(`((CraftServer) sender.getServer()).getServer().registryAccess()` — confine that cast; or pass a
RegistryAccess supplier into ReplayCommand at construction from the plugin). Log a STABLE line the IT can
grep: `"Verify <filename>: decoded=<n> errors=<m>"` and, on errors, log the first few problems. Keep the
existing start/stop/clip behavior. Resolve the file: search `getDataFolder()/replays/<name>` then
`getDataFolder()/clips/<name>`; reply "Unknown replay file" if neither exists. To get the plugin data dir
+ registry access into the command, pass them via the constructor (extend `ReplayCommand`'s constructor;
update the plugin wiring). Decoding is CPU-only on immutable registries — running it directly in the
command (main thread) is acceptable for an admin/verify command; if you prefer, offload via
`PlatformScheduler.async` and log on completion (either is fine — keep the log line format stable).

- [ ] **Step 3: Build** — `./gradlew build` → BUILD SUCCESSFUL (compiles the NMS decode recipe), unit tests green. NMS confined to `verify/` (+ `snapshot/`, `capture/ChannelAccess`).

- [ ] **Step 4: Commit**
```bash
git add src/main/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "feat: ReplayDecodeVerifier + /replay verify — decode a .flashback through the real clientbound codec"
```

---

### Task 2: `DecodeVerifyIT` — record varied bot actions, then decode-verify

**Files:** Create `src/test/java/dev/zeffut/flashbackserver/verify/DecodeVerifyIT.java`. May extend `BotClient` with a couple of action helpers.

- [ ] **Step 1 (optional): give `BotClient` a way to generate varied packets.** If easy, add helpers to
make the bot move/look so the server streams diverse packets (movement, rotation). If MCProtocolLib makes
sending serverbound movement awkward, SKIP — just staying connected for a few seconds already yields
chunks, entity, time, keepalive, etc. (enough diversity to exercise the decoder). Note which you did.

- [ ] **Step 2: Write `DecodeVerifyIT`** (`@Tag("integration")`):
record a bot, stop, then `/replay verify` the produced recording file and assert
`decoded=<n>` with n>0 and `errors=0`. Do the same for a clip (arm, save, verify). Parse the
`Verify <file>: decoded=<n> errors=<m>` log line via a small `awaitLogLine` + a regex on the captured
server stdout (extend `PaperTestServer` with a helper to fetch a matching log line's content if needed,
or reuse `awaitLogLine` to confirm the `errors=0` substring directly).
```java
package dev.zeffut.flashbackserver.verify;

import dev.zeffut.flashbackserver.harness.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class DecodeVerifyIT {
    @Test
    void recordedReplayDecodesCleanly(@TempDir Path dir) throws Exception {
        int port = 25608;
        try (PaperTestServer server = PaperTestServer.start(dir, port)) {
            String file;
            try (BotClient bot = BotClient.connect("127.0.0.1", port, "VerifyBot")) {
                assertTrue(bot.awaitJoin(60), "bot did not join");
                server.sendCommand("replay start players VerifyBot");
                assertTrue(server.awaitLogLine("Recording VerifyBot", 15), "start not confirmed");
                Thread.sleep(4000);
                server.sendCommand("replay stop players VerifyBot");
                assertTrue(server.awaitLogLine("Saved replay:", 20), "stop not confirmed");
            }
            Path replays = server.runDir().resolve("plugins/FlashbackServer/replays");
            try (Stream<Path> files = Files.list(replays)) {
                file = files.filter(p -> p.toString().endsWith(".flashback")).findFirst()
                    .orElseThrow(() -> new AssertionError("no replay produced")).getFileName().toString();
            }
            server.sendCommand("replay verify " + file);
            // require a verify line reporting zero errors and a positive decode count
            assertTrue(server.awaitLogLine("Verify " + file + ":", 20), "verify did not run");
            assertTrue(server.awaitLogLine("errors=0", 5), "decode reported errors (see [paper] log)");
            assertFalse(server.awaitLogLine("decoded=0 ", 1), "decoded zero packets");
        }
    }
}
```
(Adjust the assertion mechanics to however `awaitLogLine` works; the key gate is: a `Verify <file>:` line
appears, it reports `errors=0`, and a positive `decoded` count. If `awaitLogLine` can't express
"errors=0 on the SAME line", add a `PaperTestServer.lastLogLineContaining(String)` helper returning the
full line so the test can parse `decoded`/`errors` ints. Implement that helper if needed.)

- [ ] **Step 3: Run the IT** — `./gradlew integrationTest --tests '*DecodeVerifyIT'` → PASS (every game
packet in a real recording decodes through the clientbound codec with zero errors). If errors>0, READ the
logged problems — that's a REAL synthesis/capture bug to fix (e.g. a malformed snapshot packet). Fix the
root cause (SnapshotBuilder / capture), do not weaken the assertion. This is the payoff: it tells us
whether our bytes are genuinely client-decodable.

- [ ] **Step 4: Append the approach + GL boundary to `docs/research/r3-spike.md`**, then run full suites + commit.
Run `./gradlew test integrationTest` → all green.
```bash
git add src/test/java/dev/zeffut/flashbackserver/ src/main/java/dev/zeffut/flashbackserver/ docs/research/r3-spike.md
git -c commit.gpgsign=false commit -m "test: decode-replay IT proves recordings/clips are client-decodable (errors=0)"
```

---

## RV exit criteria
- `/replay verify <file>` decodes a replay through the real clientbound PLAY codec and reports
  `decoded=<n> errors=<m>`.
- `DecodeVerifyIT` proves a real recording (and a clip) decode with `errors=0`, `decoded>0` — i.e. every
  stored packet is a valid, client-decodable Minecraft packet. This is the strongest autonomous render
  proxy.
- `docs/research/r3-spike.md` documents that decode-verify is the autonomous gate and that GL pixel
  rendering remains a (now-optional) human spot-check.
- `./gradlew test` + `integrationTest` green.

## Known follow-ups
- Decode-verify currently checks the FIRST chunk; extend to all chunks when chunk rollover lands.
- If `errors>0` surfaces a synthesis gap (e.g. registry-dependent packet can't decode without config
  data), that directly informs the registry/config-data follow-up for full fidelity.
- A real headless GL client running Flashback is out of scope (macOS headless GL + proprietary mod);
  decode-verify + an optional human spot-check are the gates.
