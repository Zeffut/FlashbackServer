# R3a — Renderable Full Recordings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a `/replay start players <p>` recording actually RENDER in the real Flashback client by
writing a real initial-state snapshot at tick 0, synthesized from the live server state — instead of
the current empty snapshot. Scope: full recordings (clips = R3b later).

**Architecture:** A `snapshot` package. `SnapshotBuilder` produces the ordered initial-state actions
(config/login packets + a `create_local_player` action + position + player-info + the chunks around the
player), each as raw id+payload bytes in the same encoding our capture uses. The bytes come from
serializing live NMS packets through the same path the encoder uses. `RecordingManager.start` builds the
snapshot on the player's region thread and hands it to `FlashbackRecorder`, which writes it into the
chunk's snapshot block. Verification is layered (structural + decode-replay), with the real visual
render staying a P6 human spot-check.

**Tech Stack:** Java 21, Paper 1.21.5 (paperweight-userdev, Mojang-mapped), NMS packet construction,
the P0 format module, P3 `PlatformScheduler`, the P1 harness, JUnit 5. No ProtocolLib.

## Background / research
See `docs/research/r3-initial-state.md`. Minimal renderable snapshot (ordered): config-phase registry
data → `ClientboundLoginPacket` → `flashback:action/create_local_player` (Flashback-synthetic action,
NOT a wire packet) → `ClientboundPlayerPositionPacket` → `ClientboundPlayerInfoUpdatePacket` → ≥1
`ClientboundLevelChunkWithLightPacket` → trailing `next_tick`. Capture/encoding point and game-packet
action format are documented in `docs/format/flashback-format.md`.

---

### Task 1: NMS synthesis spike (GO/NO-GO)

Prove we can produce the minimal snapshot packet bytes from a live `ServerPlayer` on Paper 1.21.5, and
nail the `create_local_player` action byte layout from Flashback's source (clean-room).

**Files:** Modify `FlashbackServerPlugin` (temporary probe); document in `docs/research/r3-spike.md`.

- [ ] **Step 1: Read Flashback's `ActionCreateLocalPlayer` (clean-room)** to get the EXACT payload byte
layout (field order/types). Use the tree API + raw files:
`https://api.github.com/repos/Moulberry/Flashback/git/trees/master?recursive=1` → find
`action/ActionCreateLocalPlayer*` and read it. Document the field order (e.g. UUID, x/y/z doubles,
yaw/pitch floats, head-yaw, velocity, GameProfile, gamemode) and the exact write calls.

- [ ] **Step 2: Temporary synthesis probe.** In a temp `/synthtest` command or on a debug hook, for an
online `Player`, obtain the `ServerPlayer` (via `ChannelAccess`-style cast `((CraftPlayer)p).getHandle()`)
and attempt to build + serialize to bytes (varint id + fields, matching our capture encoding):
  - `ClientboundPlayerPositionPacket` for the player's current position,
  - `ClientboundPlayerInfoUpdatePacket` adding the player (ADD_PLAYER + UPDATE_LISTED actions),
  - `new ClientboundLevelChunkWithLightPacket(LevelChunk, LevelLightEngine, null, null)` for the chunk at
    the player's position (get the chunk from `serverPlayer.level().getChunkAt(blockpos)` or the chunk
    source),
  - `ClientboundLoginPacket` — investigate how to construct it (it needs the common player spawn info);
    if direct construction is hard, note it (the cached-real-login fallback is in Step 4).
  To serialize a packet to id+payload bytes the way the encoder does, use the PLAY clientbound
  `ProtocolInfo`/codec: find how Paper exposes the clientbound game `StreamCodec`/`ProtocolInfo` (e.g.
  `GameProtocols.CLIENTBOUND_TEMPLATE` bind, or reuse the connection's protocol). Encode each packet into
  a `RegistryFriendlyByteBuf` (registry access from `serverPlayer.registryAccess()`), read the bytes out,
  and log the leading varint (packet id) + length for each. The KEY question: can we get a clientbound
  PLAY encoder/codec to serialize these to the exact id+payload bytes?
  Run via the harness (boot Paper, bot joins, trigger the probe, capture log).

- [ ] **Step 3: Try the registry/login the practical way — cache the real config+login.** Inject a raw
capture handler EARLY (at `PlayerLoginEvent` or earlier) and log whether we capture the configuration
registry packets + the `ClientboundLoginPacket` for a connecting bot. These are session-invariant, so
caching them per player and reusing them in the snapshot avoids re-running the config phase. Confirm
whether early injection captures them and at what point.

- [ ] **Step 4: Document `docs/research/r3-spike.md` + verdict.** State, concretely:
  - the working way to serialize a clientbound PLAY packet to id+payload bytes on Paper 1.21.5 (the
    encoder/codec + RegistryFriendlyByteBuf recipe), with observed example ids/sizes;
  - whether each of position / player-info / chunk packets was built successfully;
  - the `create_local_player` payload layout;
  - the registry/login strategy (synthesize vs cache-real-via-early-injection) and which works;
  - GO (we can assemble the minimal snapshot) or BLOCKED (what's missing).

- [ ] **Step 5: Remove the probe, keep the doc, commit.** Revert `FlashbackServerPlugin`. `./gradlew build`
green. `gitignore` any spike log.
```bash
git add docs/research/r3-spike.md .gitignore src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java
git -c commit.gpgsign=false commit -m "docs: R3a synthesis spike — serialize live snapshot packets on Paper 1.21.5"
```
If BLOCKED, STOP and report — we revisit the approach (e.g. lean harder on cached-real-packets, or
the fake-connection config rerun from the research doc).

---

### Task 2: `PacketSerializer` + `SnapshotBuilder`

Turn the spike recipe into reusable code that yields the snapshot action list.

**Files:**
- Create `src/main/java/dev/zeffut/flashbackserver/snapshot/PacketSerializer.java` — live `Packet<?>` → id+payload `byte[]`
- Create `src/main/java/dev/zeffut/flashbackserver/snapshot/CreateLocalPlayerAction.java` — builds the `create_local_player` payload bytes
- Create `src/main/java/dev/zeffut/flashbackserver/snapshot/SnapshotBuilder.java` — ordered `List<ReplayAction>` from a Player
- Create `src/main/java/dev/zeffut/flashbackserver/capture/ConfigCache.java` (if Step 3 chose caching) — caches config+login bytes per player from early injection
- Tests: a unit test for `CreateLocalPlayerAction` byte layout against the documented field order; the rest is server-dependent (covered by Task 4 IT).

- [ ] **Step 1: `PacketSerializer.toBytes(ServerPlayer context, Packet<? super ClientGamePacketListener> packet) -> byte[]`**
implementing the spike's encoder recipe (clientbound PLAY codec + `RegistryFriendlyByteBuf` from the
player's registry access). All NMS confined here + `SnapshotBuilder`. Fail-fast with a clear message.

- [ ] **Step 2: `CreateLocalPlayerAction.payload(ServerPlayer) -> byte[]`** writing exactly the field
order documented in Task 1 (UUID, position doubles, rotation floats, etc.) using `VarCodec`/raw
`DataOutput` to match Flashback's writer. Add `CreateLocalPlayerActionTest` asserting the byte layout for
a fixed synthetic input (no server needed — construct from primitive inputs, not a real ServerPlayer; if
it must take a ServerPlayer, make the byte-writing logic a static testable method taking primitives).

- [ ] **Step 3: `SnapshotBuilder.build(Player) -> List<ReplayAction>`** assembling, in order: cached
config/login actions (or synthesized login) → `create_local_player` action → position → player-info →
chunk packets for the loaded chunks within the player's view distance → (the recorder appends a
`next_tick` after the snapshot as usual). Each packet wrapped as `flashback:action/game_packet` with the
serialized bytes, except `create_local_player` which uses its own identifier. Must run on the player's
region thread (chunk access). Build green; commit.
```bash
git add src/main/java/dev/zeffut/flashbackserver/snapshot/ src/main/java/dev/zeffut/flashbackserver/capture/ \
        src/test/java/dev/zeffut/flashbackserver/snapshot/
git -c commit.gpgsign=false commit -m "feat: SnapshotBuilder synthesizes the initial-state snapshot from live server state"
```

---

### Task 3: Write the snapshot into recordings

**Files:** Modify `FlashbackRecorder`, `RecordingManager`, `ReplayFiles`.

- [ ] **Step 1: Thread the snapshot through `ReplayFiles.write`** — add a `byte[] snapshot` parameter
(currently always `new byte[0]`), passing it as the first arg to `ChunkWriter.write(snapshot, actions)`.
The snapshot bytes are produced by encoding the `SnapshotBuilder` action list with `ChunkWriter`'s
action-record encoding — add a small `ChunkWriter.encodeActions(List<ReplayAction>) -> byte[]` helper (the
action-record portion only, no magic/registry) OR have `SnapshotBuilder` return the encoded snapshot
bytes directly. (Pick one; the snapshot block is "opaque sequence of action records" per the format doc,
using the SAME registry as the chunk — so the chunk's registry must include the snapshot's action
identifiers. Ensure `ChunkWriter.write` builds its registry from BOTH the snapshot actions and the live
actions. Update `ChunkWriter.write` signature to accept the snapshot as a `List<ReplayAction>` instead of
raw bytes, building one registry over snapshot+stream and encoding the snapshot block from it. This keeps
ids consistent — IMPORTANT.) Update `FlashbackValidatorTest`/`ChunkCodecTest`/`FlashbackRecorderTest`
call sites to the new `ChunkWriter.write(List<ReplayAction> snapshotActions, List<ReplayAction> streamActions)` shape; keep round-trip behavior.

- [ ] **Step 2: `FlashbackRecorder`** gains a `setSnapshot(List<ReplayAction>)` (or constructor arg) used
at `stop()` time to pass the snapshot actions to `ReplayFiles.write`. `RecordingManager.start` builds the
snapshot via `SnapshotBuilder.build(player)` on the region thread and sets it on the recorder.

- [ ] **Step 3: Build + unit tests green; commit.**
```bash
git add src/main/java/dev/zeffut/flashbackserver/ src/test/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "feat: write a real initial-state snapshot block into recordings"
```

---

### Task 4: Verification — structural + decode-replay + IT

**Files:** Extend `FlashbackValidator`; add a decode-replay test; extend `RecordingIT`.

- [ ] **Step 1: Extend `FlashbackValidator`** with an optional stricter check
`validateRenderable(Path) -> Report` that, in addition to structural validity, asserts the FIRST chunk's
snapshot block decodes and contains: a login packet action, a `create_local_player` action, a position
action, and ≥1 chunk-packet action. (Identify packets by the leading varint id where needed — record the
expected 1.21.5 clientbound ids for login/position/chunk in a small constants block, sourced from the
spike. Be tolerant: assert presence by action identifier + a plausible id, not exact field contents.)
Add unit tests using a `SnapshotBuilder`-shaped fixture (hand-built action list with the right
identifiers/ids) — no server needed.

- [ ] **Step 2: Decode-replay smoke (best-effort).** If feasible with the test-scope MCProtocolLib
codec, add a test that feeds the snapshot's packet bytes through the client's PLAY decoder and asserts no
decode exception. If MCProtocolLib can't easily decode raw clientbound bytes in isolation, SKIP and note
it — the structural check + the IT are the gate; document the limitation.

- [ ] **Step 3: Strengthen `RecordingIT`** (or add `RenderableRecordingIT`): after a normal record/stop,
assert `FlashbackValidator.validateRenderable(file).valid()` — i.e. the produced file now has a real
snapshot with login + chunk + position. Run `./gradlew integrationTest --tests '*RecordingIT*'` → PASS.

- [ ] **Step 4: Full suites + commit.**
Run `./gradlew test integrationTest` → all green.
```bash
git add src/main/java/dev/zeffut/flashbackserver/format/FlashbackValidator.java src/test/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "test: assert recordings carry a renderable initial-state snapshot (login+chunk+position)"
```

---

## R3a exit criteria
- The spike documents a working recipe to serialize live clientbound PLAY packets to id+payload bytes on
  Paper 1.21.5, and the `create_local_player` byte layout (`docs/research/r3-spike.md`).
- `SnapshotBuilder` produces an ordered snapshot (config/login + create_local_player + position +
  player-info + chunks) from a live player.
- Recordings write that snapshot into the chunk's snapshot block (no longer empty).
- `FlashbackValidator.validateRenderable` asserts the snapshot contains login + create_local_player +
  position + ≥1 chunk; `RecordingIT` proves a real recording passes it.
- `./gradlew test` + `integrationTest` green.

## Known follow-ups
- **R3b — renderable clips:** periodic keyframe snapshots in the ring buffer; on save emit the
  nearest-preceding keyframe (snapshot must be window-start state, not "now").
- **Reference-file golden test** + the **P6 human spot-check** are the final proof of real-client render.
- Fidelity extras (entities, weather, scoreboard, tablist beyond self, world border) — later.
- Correct protocol/data versions in metadata (replace the 769/4189 placeholders) — natural to fix here
  since the spike exposes the live versions.
