# R3 — Giving `.flashback` recordings a correct INITIAL STATE so they render

> Clean-room research. We read public source to learn the FORMAT/PROTOCOL and the server-side
> technique only. **No code was copied.** Flashback is source-available/proprietary — read for
> format only. The Arcade `arcade-replay` library (the engine behind senseiwells/ServerReplay)
> is **MIT-licensed** and is a direct precedent for the server-side job we must do.
>
> Date: 2026-06-04. Author: research agent.

## Sources read

Flashback (Moulberry/Flashback, `master`) — format/protocol reference, read-only:
- `src/main/java/com/moulberry/flashback/record/Recorder.java` — `writeSnapshot()` ordering
- `src/main/java/com/moulberry/flashback/action/ActionCreateLocalPlayer.java`
- `src/main/java/com/moulberry/flashback/action/ActionConfigurationPacket.java`
- `src/main/java/com/moulberry/flashback/action/ActionLevelChunkCached.java`
- `src/main/java/com/moulberry/flashback/playback/ReplayServer.java`

ServerReplay (senseiwells/ServerReplay, MIT) — confirmed the recorder core lives in the
`net.casualchampionships:arcade-replay` artifact (`build.gradle.kts`, `libs.versions.toml`).

Arcade (CasualChampionships/arcade, branch `26.1`, MIT) — the actual server-side recorder, the
closest precedent to our task:
- `arcade-replay/.../recorder/ReplayRecorder.kt` — base recorder, init lifecycle
- `arcade-replay/.../recorder/rejoin/RejoinConnection.kt` — fake in-memory `Connection`
- `arcade-replay/.../recorder/rejoin/RejoinedReplayPlayer.kt` — replays the join pipeline
- `arcade-replay/.../recorder/rejoin/RejoinConfigurationPacketListener.kt` — drives config phase
- `arcade-replay/.../recorder/player/ReplayPlayerRecorder.kt`
- `arcade-replay/.../recorder/chunk/ReplayChunkRecorder.kt`
- `arcade-replay/.../recorder/ChunkSender.kt`
- `arcade-replay/.../io/writer/flashback/FlashbackWriter.kt`, `FlashbackChunkedWriter.kt`
- `arcade-replay/.../util/flashback/FlashbackAction.kt`

Our own code grounding the recommendation:
- `src/main/java/dev/zeffut/flashbackserver/record/FlashbackRecorder.java`
- `src/main/java/dev/zeffut/flashbackserver/format/ChunkWriter.java`
- `docs/format/flashback-format.md`

---

## Q1 — What the Flashback client needs in the snapshot to render

The snapshot block is just an action stream (same encoding as the live stream) wrapped between a
snapshot-start and snapshot-end marker, ending with a `next_tick`. On a seek/open, the reader
replays the snapshot to reconstruct world state, then plays the live stream forward from there. So
the snapshot must, in order, reproduce **exactly what a freshly-joining vanilla client receives**.

### Ordered set Flashback's `Recorder.writeSnapshot()` emits

**Configuration phase** (written as `flashback:action/configuration_packet`, decoded with the
CONFIGURATION codec):
1. `ClientboundUpdateEnabledFeaturesPacket`
2. `ClientboundRegistryDataPacket` — one per registry (dimension types, biomes, etc.)
3. `ClientboundUpdateTagsPacket`
4. Resource-pack pop + push packets (only if server has packs)

**Play phase** (written as `flashback:action/game_packet`, PLAY codec), in this order:
5. `ClientboundLoginPacket` — entity id, dimension/level info, hashed seed, gamemode, view
   distance, spawn. **This is the keystone packet — the client switches to PLAY and builds the
   local player from it.**
6. **`flashback:action/create_local_player`** — a synthetic Flashback action (not a vanilla
   packet) carrying the camera/local-player identity (see Q-field-layout below).
7. `ClientboundPlayerInfoUpdatePacket` — tablist entries (listed players, profiles, properties)
8. `ClientboundTabListPacket` — header/footer
9. `ClientboundBossEventPacket` — per active boss bar
10. `ClientboundSetPlayerTeamPacket` — per team
11. `ClientboundSetObjectivePacket` + `ClientboundSetDisplayObjectivePacket` + `ClientboundSetScorePacket`
12. `ClientboundInitializeBorderPacket` — world border
13. `ClientboundSetTimePacket` — game time / day time
14. `ClientboundSetDefaultSpawnPositionPacket`
15. Weather as `ClientboundGameEventPacket` (rain start/stop, rain level, thunder level)
16. `ClientboundLevelChunkWithLightPacket` — every loaded chunk, sorted by distance to player.
    In Flashback these are de-duplicated into `level_chunk_caches/<index>` files and referenced by
    `flashback:action/level_chunk_cached` (varint index).
17. Per entity (excluding local player): `ClientboundAddEntityPacket`, then
    `ClientboundSetEntityDataPacket` (metadata), `ClientboundUpdateAttributesPacket`,
    `ClientboundSetEquipmentPacket`, `ClientboundSetPassengersPacket`, `ClientboundSetEntityLinkPacket`
    as applicable.
18. `ClientboundMapItemDataPacket` — per map
19. (optional) local player stats: `ClientboundSetExperiencePacket`, `ClientboundSetHealthPacket`,
    `ClientboundSetHeldSlotPacket`, hotbar `ClientboundContainerSetSlotPacket`
20. `writeCustomSnapshot()` extension point.

### MINIMAL VIABLE set (what is actually load-bearing to get pixels on screen)

To "render" at all, the snapshot must establish: dimension+registries → local player → ground to
stand on:

1. Configuration: enabled features, **registry data** (at least dimension type + biome + the
   datapack registries the client needs to decode the level), tags.
2. `ClientboundLoginPacket` (PLAY join).
3. `flashback:action/create_local_player` (camera entity — without it Flashback has no viewpoint).
4. `ClientboundPlayerPositionPacket` (or Flashback's `accurate_player_position`) so the camera is
   placed in the world.
5. `ClientboundPlayerInfoUpdatePacket` for the local player's own entry (needed for skin/name and,
   in practice, for the client to be happy).
6. **≥1 `ClientboundLevelChunkWithLightPacket`** around the camera (otherwise: void / nothing
   renders).
7. A closing `next_tick`.

Everything else (entities, tablist of others, scoreboard, weather, time, world border, maps)
improves fidelity but is not required for a first frame. **Registries + Login + create_local_player
+ position + ≥1 chunk is the floor.**

### Field layout of `flashback:action/create_local_player`

Confirmed identifier `flashback:action/create_local_player`. The Flashback `ReplayServer` decode
side delegates to a handler not visible in `ReplayServer.java`, but Arcade's MIT writer
(`FlashbackWriter.writePlayer` / `FlashbackAction.CreatePlayer`) serializes, in order:
`uuid`, position `x,y,z` (doubles), rotation `yaw,pitch` (floats), head rotation, velocity, game
profile (name + properties), gamemode identifier. Match that order when we synthesize it.

---

## Q2 — How Arcade/ServerReplay builds initial state server-side (the precedent)

This is the single most important finding. **Arcade does NOT capture the real join packets off a
socket. It re-runs the server's own join pipeline against a fake connection and records the output.**

### The "rejoin" trick

- `RejoinConnection` **extends the vanilla `Connection`** with `PacketFlow.SERVERBOUND`. It is a
  purely in-memory connection with no socket. The server's packet-listeners send packets "to the
  client" through it, but instead of going to a network it is intercepted.
- `RejoinConfigurationPacketListener` **extends `ServerConfigurationPacketListenerImpl`** and
  overrides `send()` so every outgoing packet is routed to `recorder.record(packet)`. It calls
  `startConfiguration()` and a custom `runConfigurationTasks()` that iterates the queued vanilla
  `ConfigurationTask`s, invokes `task.start(...)`, and — crucially — does **not wait for client
  acks**: for the registry-sync task it calls `handleResponse(...)` itself. This makes the server
  emit the real `ClientboundUpdateEnabledFeaturesPacket`, `ClientboundRegistryDataPacket`(s),
  `ClientboundUpdateTagsPacket`, resource-pack packets — exactly what a joining client gets —
  synchronously, with no client present.
- `RejoinedReplayPlayer.rejoin(player, recorder)` builds a player instance that shares the real
  player's entity id + profile, runs the configuration listener above, then transitions to a
  `RejoinGamePacketListener` (extends `ServerGamePacketListenerImpl`). Its `place()` builds and
  sends the `ClientboundLoginPacket` (from level data, player count, difficulty, spawn) plus the
  standard post-login game packets (player info, recipes, objectives/teams, boss events, mob
  effects), all captured via the overridden `send()`.
- `ReplayRecorder` (base) tracks the protocol state machine
  (LOGIN → CONFIGURATION → PLAY) so the writer tags each captured packet with the right phase, and
  detects `ClientboundLoginPacket` to finalize initialization.

### (a) Player recorder vs (b) chunk recorder

- **Player recorder** (`ReplayPlayerRecorder.initialize()`): calls `RejoinedReplayPlayer.rejoin(...)`
  on the **real** online player, then records `ClientboundAddEntityPacket` for the player, map data
  for any maps in inventory, and `sendChunksAndEntities()` for everything in the player's view
  distance.
- **Chunk recorder** (`ReplayChunkRecorder.initialize()`) — the analogue to our "no real client"
  problem: it **fabricates a dummy `ServerPlayer`** with default `ClientInformation`, wraps it in a
  `ReplayChunkGamePacketListener`, and stuffs a `RejoinConnection` whose `packetContext` carries a
  synthetic `GAME_PROFILE` and the `SERVER_INSTANCE`. It computes a spawn position
  (`Heightmap.Types.WORLD_SURFACE` or configured coords), force-loads the center chunk
  (`level.getChunk(...)`), runs `RejoinedReplayPlayer.rejoin(...)` to get login+config, then sends
  the chunk area's chunks/entities. The dummy is invisible and tablist-hidden so it does not leak.

### `ChunkSender` — serializing a chunk to a packet from live state (no client)

`ChunkSender.sendChunk()` constructs directly:
`new ClientboundLevelChunkWithLightPacket(chunk, chunk.level.lightEngine, null, null)` where
`chunk` is a `LevelChunk` and the light engine is `LevelLightEngine`. The two trailing nulls are
the partial-section bitsets (null = whole chunk). It also adds tracked entities present in each
chunk (`ClientboundAddEntityPacket` etc.) and emits `ClientboundSetEntityLinkPacket` /
`ClientboundSetPassengersPacket` for leashes/riders. **This proves a chunk can be serialized to the
exact wire packet from a live `LevelChunk` purely server-side.**

### How Arcade maps captured packets to the Flashback file

`FlashbackWriter` + `FlashbackChunkedWriter`:
- `beginInitialization()` → `startSnapshot()`; `endInitialization()` → `endSnapshot()` then a
  `NextTick`. So the entire init sequence becomes the snapshot block.
- Phase routing: `CONFIGURATION` packets → `ConfigurationPacket` action; `PLAY` packets →
  `GamePacket` action.
- `ClientboundLevelChunkWithLightPacket` → `writeCachedChunk()` → dedup into a chunk-cache file +
  `CacheChunk` (`flashback:action/level_chunk_cached`) action carrying a varint index.
- Local player → `CreatePlayer` (`flashback:action/create_local_player`) action.
- `ClientboundMoveEntity*` → `MoveEntities` action (a movement-batching optimization; optional).

`FlashbackAction` enum identifiers (confirmed):
`flashback:action/next_tick`, `.../game_packet`, `.../configuration_packet`,
`.../create_local_player`, `.../level_chunk_cached`, `.../move_entities`,
`.../simple_voice_chat_sound_optional`, and `arcade:action/encoded_simple_voice_chat_sound_optional`.

---

## Q3 — Feasibility on Paper 1.21.5: two approaches

### Approach A — inject the Netty capture EARLIER to record the real join sequence

**Earliest reliable inject point on Paper:** the channel is created during the handshake, long
before any Bukkit event. To capture configuration + login(play) + initial chunks you must be on the
pipeline before those packets are written, i.e. at channel-init time. Options:

- A `ChannelInitializer` / `ChannelFutureListener` on the server's `ServerBootstrap` child pipeline
  (NMS `ServerConnectionListener.channels` / the `ServerBootstrap` `childHandler`). This is the
  genuine earliest point but requires NMS reflection into the connection list and is fragile across
  Paper builds.
- A more practical Paper point: hook `PlayerLoginEvent` or even `AsyncPlayerPreLoginEvent`/
  `PlayerHandshakeEvent`. **But these still fire during/after login and may be after some
  configuration packets are queued.** The cleanest is to inject in the channel pipeline as soon as
  the `Connection` exists for that channel — which in practice means wrapping the server's child
  channel initializer at plugin enable, so every new connection gets our handler before the
  vanilla login handler runs.

**What we would capture:** the full real sequence — configuration packets (registries/tags/features),
`ClientboundLoginPacket`, then all post-login game packets and the initial chunk stream the server
genuinely sends. That is a *complete, authentic* initial state for a recording that **starts at
connection**. We'd still need to synthesize the `create_local_player` action (it is a Flashback
construct, never sent on the wire) and tag config-phase vs play-phase packets (we can, because we
see the protocol transition / the `ClientboundLoginPacket`).

**Limitation (decisive):** Approach A only helps recordings that begin at connect. For
`/replay start` mid-session, and for clips, the join/config/initial-chunk packets were sent minutes
or hours ago and are gone. Approach A cannot serve them. It also doesn't reflect *current* world
state for a mid-session start even if you had buffered the old packets.

**Verdict:** Approach A is viable and gives the most authentic state for connect-time recordings,
but it is insufficient on its own and adds NMS-pipeline fragility. Useful as an optimization, not a
foundation.

### Approach B — synthesize the snapshot from live server state on demand

This is what Arcade does, and it is **the recommended foundation**. On Paper/NMS at an arbitrary
moment we can, from the live `ServerPlayer` / `ServerLevel`:

- **`ClientboundLoginPacket`** — buildable from `ServerLevel` data, `ServerPlayer`,
  `MinecraftServer` (player count/difficulty/spawn). Vanilla builds it in
  `PlayerList.placeNewPlayer` / `ServerGamePacketListenerImpl`; we can mirror that construction. The
  cleanest path is Arcade's: re-run the join pipeline against a fake `Connection`.
- **Configuration packets** — replay the vanilla `ConfigurationTask`s (registry sync, tags, enabled
  features) against a fake `ServerConfigurationPacketListenerImpl`, calling `task.start()` and
  feeding our own `handleResponse` so no client ack is needed. Output captured = the config packets.
- **`ClientboundPlayerPositionPacket`** — trivially `new` from the player's current pos/rotation.
- **`ClientboundPlayerInfoUpdatePacket`** — `ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(Collection<ServerPlayer>)`
  (vanilla helper) builds the full add-player entry from live `ServerPlayer`s.
- **Chunks** — for each chunk around the player:
  `new ClientboundLevelChunkWithLightPacket(LevelChunk, LevelLightEngine, null, null)`. Get chunks
  via `ServerLevel.getChunkSource().chunkMap` and the player's `ChunkTrackingView`
  (`chunkTrackingView.forEach(...)`), and `serverLevel.getChunk(x, z)` / the loaded `LevelChunk`s.
  This is exactly `ChunkSender.sendChunk`.
- **Entities** — `ClientboundAddEntityPacket(entity)`, plus
  `entity.getEntityData().getNonDefaultValues()` → `ClientboundSetEntityDataPacket`,
  `ClientboundUpdateAttributesPacket`, `ClientboundSetEquipmentPacket`, passengers/leash packets.
  Vanilla's `ServerEntity` already knows how to emit the spawn pairs (`ServerEntity.sendPairingData`),
  which we can reuse.

**Difficulty:** Medium-to-high but well-trodden. The hard parts are (1) the configuration-phase
re-run (must not block on client acks — Arcade shows the exact override), (2) NMS access from Paper
(we already inject Netty and read NMS in the spike, so we have the access pattern), and (3) Folia
threading if relevant (chunk reads must be on the owning region thread). No real client is required
at any step — Arcade's chunk recorder proves the whole thing runs with a *dummy* `ServerPlayer`.

**Key risk:** version-coupling to 1.21.5 Mojmap/NMS signatures (e.g. the exact
`ClientboundLevelChunkWithLightPacket` ctor arity, `createPlayerInitializing` name). These are
stable in 1.21.x but must be pinned per MC version.

---

## Q4 — Clips (the hard case)

A clip is the last N seconds, started mid-session — Approach A is impossible (join packets gone).
The clip's snapshot must represent state at the START of the clip window, then the buffered packets
replay forward.

**Subtlety about "now" vs "window start":** If you synthesize the snapshot at SAVE time, the state
is *now* (end of window), but the clip's buffered actions are deltas that were emitted from
*window-start* onward. Replaying window-start→now deltas on top of a *now* snapshot is
inconsistent — entities would jump/double-apply, blocks would be wrong. So a save-time snapshot is
**only** correct if the clip's action stream is *discarded* and the clip becomes "just the snapshot"
(a 1-frame still) — not useful. To play the buffered window, the snapshot must be the state as of
**window start**.

**Two correct options:**

- **(a) Periodic snapshot into the ring buffer (RECOMMENDED).** Every K seconds (K ≤ clip length,
  e.g. snapshot cadence = clip length, or a sliding keyframe), synthesize a full snapshot via
  Approach B and store it in the ring buffer as a keyframe alongside the packet deltas. On clip
  save, pick the newest keyframe at-or-before the window start, emit it as the snapshot, then emit
  the buffered deltas from that keyframe forward (trimming to the window). This is the standard
  "keyframe + delta" video model and is exactly analogous to how Arcade's chunk recorder takes an
  initial snapshot and then records deltas — for clips you simply take snapshots *repeatedly* so one
  is always available near any window start.
- **(b) Reconstruct window-start state from now − deltas.** Theoretically you could invert the
  buffered deltas to roll state back to window start. This is far harder and error-prone; reject it.

**Cost note:** A full snapshot (chunks for the area) is the expensive part. Mitigations: snapshot
only the chunk *area* of interest (clip cameras are usually a fixed region or a player), reuse
Flashback's chunk-cache dedup so repeated keyframes share chunk bytes, and keep cadence coarse
(e.g. one keyframe per clip-length window means you store at most ~2 keyframes for the ring).

**Recommendation for clips:** Option (a) — periodic keyframe snapshots synthesized via Approach B,
stored in the ring buffer; on save, emit nearest-preceding keyframe as the snapshot block + trimmed
deltas. Snapshot the camera's chunk area only.

---

## Q5 — Autonomous verification (no human, no Minecraft)

Layered, cheapest first:

1. **Structural snapshot assertion (cheap, do first).** Extend `FlashbackValidator` to parse the
   snapshot block as an action stream and assert it contains, in order: ≥1
   `configuration_packet` carrying registry data, exactly one `game_packet` whose decoded varint id
   == the 1.21.5 `ClientboundLoginPacket` id, one `create_local_player` action, a position
   (`ClientboundPlayerPositionPacket` game_packet or `accurate_player_position`), ≥1 chunk
   (`level_chunk_cached` referencing a present cache entry, or inline chunk packet), and a trailing
   `next_tick`. This catches the "floor" requirements from Q1 and is fully autonomous.

2. **Packet-decode replay into a client state machine (medium).** Feed the snapshot's packets,
   in order, into an MCProtocolLib (or a minimal hand-rolled) **clientbound PLAY/CONFIGURATION
   codec** in client mode and assert each decodes without throwing and that the state machine
   accepts the LOGIN→PLAY transition. This verifies the bytes are *protocol-valid* for 1.21.5, not
   merely structurally framed. Does not render, but catches malformed packet payloads (our current
   biggest unknown).

3. **Golden-file diff against a real Flashback recording (highest confidence, build once).**
   Produce one reference `.flashback` with the genuine Flashback mod (or ServerReplay) for a known
   scene, parse its snapshot, and diff the *set/order of action identifiers and packet ids* against
   ours for an equivalent scene. Exact byte-equality is unrealistic (timestamps, uuids, chunk
   bytes), so diff the *shape*: same configuration packet types, same play packet id sequence, chunk
   count in the expected ballpark. Store the reference's snapshot id-sequence as a fixture.

4. **Final visual confirmation = human (P6 spot-check).** Acknowledged: actually opening the file in
   Flashback and seeing the world render is the only true proof and stays a manual P6 step.

**Pragmatic recommendation:** ship (1) immediately as a CI gate, add (2) as the main autonomous
confidence signal (it directly attacks our risk: invalid synthesized packet bytes), and build (3)
once as a one-time fixture to lock the snapshot *shape*. Treat (4) as the human gate before release.

---

## RECOMMENDATION & decomposition

**Foundation: Approach B (synthesize the snapshot from live server state), mirroring Arcade's
rejoin technique.** It uniquely solves both full recordings AND clips AND mid-session starts with
one mechanism, requires no real client, and Arcade is a working MIT precedent we can learn the
technique from (not copy).

Proposed split:

- **R3a — Renderable full recordings (Approach B at record start).** At `/replay start` (or join),
  synthesize the snapshot from the live `ServerPlayer`/`ServerLevel`: replay configuration tasks
  against a fake config listener (registries/tags/features), build `ClientboundLoginPacket`, emit
  `create_local_player`, `ClientboundPlayerPositionPacket`, `ClientboundPlayerInfoUpdatePacket`,
  the player's view-distance chunks via `new ClientboundLevelChunkWithLightPacket(chunk,
  level.lightEngine, null, null)`, then entities. Write all of this into the snapshot block
  (currently `new byte[0]`), then keep appending the live captured `game_packet` stream as we do
  today. *Optional later optimization:* Approach A (early Netty inject) to capture the authentic
  join sequence for connect-time recordings instead of synthesizing it.

- **R3b — Renderable clips (Approach B as periodic keyframes).** Run the same snapshot synthesizer
  on a timer into the clip ring buffer (keyframe + deltas). On clip save, emit the nearest-preceding
  keyframe as the snapshot block and the trimmed deltas after it. Snapshot only the clip camera's
  chunk area; lean on Flashback chunk-cache dedup across keyframes.

- **R3c — Autonomous verification.** Snapshot structural assertions in `FlashbackValidator` (gate),
  + MCProtocolLib client-codec decode replay of the snapshot, + a one-time golden-file shape diff
  against a real Flashback recording. Human P6 spot-check remains the final visual gate.

### Difficulty / risk

- **Config-phase re-run without client acks** — medium; Arcade shows the precise override
  (`runConfigurationTasks` calling `handleResponse` itself). Risk: Paper/Fabric differences in the
  configuration task list.
- **NMS version coupling (1.21.5)** — medium; pin exact Mojmap signatures
  (`ClientboundLevelChunkWithLightPacket` 4-arg ctor, `ClientboundPlayerInfoUpdatePacket.createPlayerInitializing`,
  `LevelLightEngine`, `ChunkTrackingView`). Stable within 1.21.x.
- **Threading / Folia** — medium; chunk + entity reads must run on the owning region thread; reuse
  the platform scheduler already in the project (`platform/PlatformScheduler.java`).
- **`create_local_player` exact byte order** — low; confirmed field order from Arcade's writer; verify
  against a real Flashback file in R3c.

### Blockers / unknowns

- The `create_local_player` decode side in Flashback's `ReplayServer` delegates to a handler we
  could not see in `ReplayServer.java`; field order is taken from Arcade's MIT writer and must be
  byte-verified against a real recording (R3c golden file).
- **Existing-code bug spotted while grounding this (flag, not part of research):** our
  `ChunkWriter.write` emits the action registry as `(varint index, string identifier)` per entry,
  but the documented Flashback format (`docs/format/flashback-format.md`) specifies `action_count`
  then *identifiers only*, with the entry's position = its id. If our reader and Flashback expect
  position-as-id, the extra per-entry varint will desync parsing. Verify before relying on R3c
  decode tests.
- Whether Paper's earliest channel-init inject (Approach A) is stable enough to be worth it is
  unresolved; Approach B does not depend on it.
