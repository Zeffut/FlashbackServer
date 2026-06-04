# F1 — Snapshot Fidelity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make recorded snapshots COMPLETE so the real Flashback client renders a full first frame: emit
configuration-phase data (enabled features, registry data, tags) BEFORE the login packet, include the
player's full view-distance chunks (not just one), and stamp the real live protocol/data versions in
metadata. Decode-verify (incl. configuration packets) stays the autonomous gate.

**Architecture:** `SnapshotBuilder` is extended to prepend `flashback:action/configuration_packet` actions
(features + registries + tags, encoded with the CONFIGURATION codec) then the existing PLAY actions, and to
loop the view-distance chunks. `PacketSerializer` gains a config-codec encode path. A `McVersions` helper
exposes live protocol/data versions. `ReplayDecodeVerifier` decodes configuration_packet actions with the
config codec too. All NMS stays in `snapshot/` + `verify/`.

**Tech Stack:** Java 21, Paper 1.21.5 NMS (config + game codecs), the P1 harness, JUnit 5.

## Background
Spike recipe (game codec) + create_local_player layout: `docs/research/r3-spike.md`. Format: config packets
use identifier `flashback:action/configuration_packet`, encoded with the configuration protocol; PLAY packets
use `flashback:action/game_packet`. The snapshot is replayed in order, so config packets (which establish
registries) must precede the login packet that transitions to PLAY.

---

### Task 1: Spike — config-phase packet synthesis (GO/NO-GO)

Prove we can produce the configuration-phase packets (enabled features, registry data, tags) from a live
server as id+payload bytes via the CONFIGURATION codec, and get live versions.

**Files:** temp probe in `FlashbackServerPlugin`; document `docs/research/f1-spike.md`.

- [ ] **Step 1: Find the recipe.** The research (`docs/research/r3-initial-state.md`) points to
`net.minecraft.server.network.config.SynchronizeRegistriesTask` driven via `start(Consumer<Packet<?>>)`
(Arcade precedent) to emit registry packets without client acks. Investigate on Paper 1.21.5:
  - registry data: construct `new SynchronizeRegistriesTask(layers)` where `layers` come from the server's
    registry layers (e.g. `server.registries()` / `LayeredRegistryAccess`); call `.start(consumer)` and
    collect the emitted packets. Confirm the exact constructor + how to obtain the registry layers.
  - enabled features: `new ClientboundUpdateEnabledFeaturesPacket(server.getWorldData()... featureFlags)` or
    `FeatureFlags`/`serverPlayer.connection`... find what compiles to get the enabled feature set.
  - tags: `new ClientboundUpdateTagsPacket(...)` — find how the server exposes the serializable tags per
    registry (e.g. `TagNetworkSerialization.serializeTagsToNetwork(registryAccess)`).
  - encode each with the CONFIGURATION codec: `ConfigurationProtocols.CLIENTBOUND.codec().encode(buf, packet)`
    (already bound, no registry decorator needed — confirm).
  - live versions: `net.minecraft.SharedConstants.getProtocolVersion()` and
    `SharedConstants.getCurrentVersion().getDataVersion().getVersion()` (or `.dataVersion()`) — confirm.

- [ ] **Step 2: Temporary probe** (entity-scheduler one-shot after a bot joins): build + serialize the
config packets, log `[cfg] <packetClass> bytes=<n> id=<leadingVarint>` for each, plus
`[ver] protocol=<p> data=<d>`. Run via a temp `@Tag("integration")` test booting Paper + a bot. Capture log.

- [ ] **Step 3: Document `docs/research/f1-spike.md`** — the working recipe for features/registries/tags
serialization + the config codec call + live-version accessors, with observed counts; VERDICT GO/BLOCKED.
If registry synthesis is blocked, note the fallback (cache real config packets via early raw injection,
per `docs/research/r3-initial-state.md`).

- [ ] **Step 4: Revert probe, keep doc, commit.** `./gradlew build` green; `gitignore` the spike log.
```bash
git add docs/research/f1-spike.md .gitignore src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java
git -c commit.gpgsign=false commit -m "docs: F1 spike — synthesize configuration-phase packets + live versions on Paper 1.21.5"
```
If BLOCKED, STOP and report.

---

### Task 2: `McVersions` + config-codec serialization + extend `SnapshotBuilder`

**Files:** `snapshot/PacketSerializer.java` (add config encode), `snapshot/McVersions.java` (new),
`snapshot/SnapshotBuilder.java` (extend), tests.

- [ ] **Step 1: `McVersions`** `src/main/java/dev/zeffut/flashbackserver/snapshot/McVersions.java`:
`static int protocolVersion()` and `static int dataVersion()` from `SharedConstants` (recipe from Task 1).
NMS confined here.

- [ ] **Step 2: `PacketSerializer.encodeConfigPacket(Packet<? super ClientConfigurationPacketListener>)`**
using `ConfigurationProtocols.CLIENTBOUND.codec().encode(buf, packet)` (no registry decorator if Task 1
confirmed it's pre-bound; else bind with the registry decorator). Returns id+payload bytes. Keep the
existing `encodeGamePacket`.

- [ ] **Step 3: Extend `SnapshotBuilder.build(Player)`** to assemble, IN ORDER:
  1. `flashback:action/configuration_packet` actions: enabled-features, each registry-data packet (from
     SynchronizeRegistriesTask), tags — encoded via `encodeConfigPacket` (recipe from Task 1).
  2. login → `flashback:action/game_packet`
  3. `flashback:action/create_local_player`
  4. position, player-info → game_packet
  5. ALL chunks within the player's view distance: iterate chunk coords in a radius of
     `level.getServer().getPlayerList().getViewDistance()` (clamp to a sane max, e.g. min(viewDistance, 8)
     for snapshot size) around `sp.chunkPosition()`, get each loaded `LevelChunk` (skip non-loaded), build
     `ClientboundLevelChunkWithLightPacket` → game_packet.
  Still must run on the player's region thread. Add a config constant or constructor param later for the
  chunk radius cap; for now a named constant `SNAPSHOT_CHUNK_RADIUS` (document it).

- [ ] **Step 4: Tests + build.** Keep `CreateLocalPlayerActionTest`. Add (if practical without a server)
nothing server-dependent here — the real check is the F1 IT in Task 4. `./gradlew build` green. Commit.
```bash
git add src/main/java/dev/zeffut/flashbackserver/snapshot/ src/test/java/dev/zeffut/flashbackserver/snapshot/
git -c commit.gpgsign=false commit -m "feat: snapshot includes config-phase data (features/registries/tags) + full view-distance chunks"
```

---

### Task 3: Live versions in metadata

**Files:** `record/RecordingManager.java`, `clip/ClipManager.java`.

- [ ] **Step 1: Replace the hardcoded `769, 4189`** in both managers with
`McVersions.protocolVersion()` / `McVersions.dataVersion()`. (FlashbackRecorder/ReplayFiles already take
the ints — just pass the live values.) Build green; unit tests green. Commit.
```bash
git add src/main/java/dev/zeffut/flashbackserver/record/ src/main/java/dev/zeffut/flashbackserver/clip/
git -c commit.gpgsign=false commit -m "feat: stamp live protocol/data versions in replay metadata"
```

---

### Task 4: Extend verification for config packets + F1 IT

**Files:** `verify/ReplayDecodeVerifier.java`, `format/FlashbackValidator.java`, a new IT.

- [ ] **Step 1: `ReplayDecodeVerifier`** — also decode `flashback:action/configuration_packet` actions via
the CONFIGURATION codec (`ConfigurationProtocols.CLIENTBOUND.codec().decode(buf)`), counting them in
`decoded` and catching errors/trailing bytes the same way. (game_packet → game codec; configuration_packet
→ config codec; skip create_local_player/next_tick.)

- [ ] **Step 2: `FlashbackValidator.validateRenderable`** — additionally assert the snapshot contains at
least one `flashback:action/configuration_packet` action (registry/config presence). Keep the existing
login/create_local_player/position/chunk checks. Update its unit tests' fixtures to include a config action.

- [ ] **Step 3: New IT `SnapshotFidelityIT`** (`@Tag("integration")`): record a bot, stop, then
`/replay verify <file>` and assert `errors=0` with a higher `decoded` count (now includes config +
multiple chunks); and assert `FlashbackValidator.validateRenderable(file).valid()` (now requires config
data too). Confirm the decoded count is materially larger than the single-chunk era (sanity that config +
chunks are present). Run `./gradlew integrationTest --tests '*SnapshotFidelityIT' --tests '*RecordingIT*' --tests '*DecodeVerifyIT'` → PASS.

- [ ] **Step 4: Full suites + commit.** `./gradlew test integrationTest` → all green.
```bash
git add src/main/java/dev/zeffut/flashbackserver/ src/test/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "test: verify config-phase packets decode + snapshot carries registry data"
```

## F1 exit criteria
- Snapshots contain config-phase data (features/registries/tags), the player's view-distance chunks, and
  the login/create_local_player/position/player-info sequence.
- Metadata stamps live protocol/data versions.
- Decode-verify decodes config + game packets with errors=0; validateRenderable requires config data.
- All suites green.

## Known follow-ups
- Snapshot chunk radius is capped (`SNAPSHOT_CHUNK_RADIUS`) to bound size; make it configurable later.
- Entities/scoreboard/tablist-beyond-self/weather/worldborder fidelity — optional polish.
- The final GL visual render remains the optional human spot-check.
