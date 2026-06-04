# R3a — Task 1 GO/NO-GO spike: synthesize snapshot packets from a live `ServerPlayer`

> Goal: prove we can build the minimal initial-state snapshot packets from a LIVE `ServerPlayer` on
> Paper 1.21.5 and serialize them to the exact `varint id + payload` bytes our capture pipeline uses
> (uncompressed, unframed — what we observe at `addBefore("encoder")`).
>
> Date: 2026-06-04. Method: temporary `SynthProbe` + `SynthSpikeIT` booting real Paper 1.21.5 via the
> harness, a bot joins, probe fires ~2s after join on the player's entity scheduler, `[synth]` lines
> captured from server stdout (Gradle test HTML report). Probe + temp test removed after the spike.

## VERDICT: **GO**

All four target PLAY packets were built from the live `ServerPlayer` and serialized to id+payload
bytes server-side, with NO real client involved. Observed (`[synth]` log, real run):

```
[synth] === probe start for Bot ===
[synth] PlayerPositionPacket     OK id=65 totalBytes=62
[synth] PlayerInfoUpdatePacket   OK id=63 totalBytes=25
[synth] LevelChunkWithLightPacket OK id=39 totalBytes=7282
[synth] LoginPacket              OK id=43 totalBytes=113
[synth] === probe end ===
```

`totalBytes` is the full encoded packet INCLUDING the leading varint id (the codec writes the id
first, then the payload). `id` is that leading varint decoded — these are the 1.21.5 clientbound PLAY
packet ids. This is exactly the byte shape our pipeline records as a `flashback:action/game_packet`.

Notably **`ClientboundLoginPacket` synthesized successfully** from live data (no cache-real fallback
needed for it — see Step 3). The only piece of the "minimal floor" NOT exercised by this spike is the
CONFIGURATION-phase registry data; the path for it is identified below and is low-risk.

---

## The WORKING serialization recipe (compiled + ran on Paper 1.21.5)

To turn a clientbound PLAY `Packet<?>` into the id+payload bytes the encoder produces:

```java
// 1. Bind the clientbound PLAY protocol template to the player's registry access.
//    RegistryFriendlyByteBuf.decorator(access) is the Function<ByteBuf,RegistryFriendlyByteBuf>
//    that ProtocolInfo.Unbound.bind(...) needs.
ProtocolInfo<ClientGamePacketListener> info =
        GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(sp.registryAccess()));

// 2. The bound ProtocolInfo's codec() is the StreamCodec the encoder uses. It writes
//    varint id + payload into a plain ByteBuf (it wraps it as a RegistryFriendlyByteBuf internally
//    via the decorator).
StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = info.codec();

// 3. Encode.
ByteBuf buf = Unpooled.buffer();
codec.encode(buf, packet);          // buf now = [varint packet id][payload], exactly the wire bytes
byte[] idAndPayload = ByteBufUtil.getBytes(buf);
buf.release();
```

Key facts discovered:
- `net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE` is a
  `ProtocolInfo.Unbound<ClientGamePacketListener, RegistryFriendlyByteBuf>`. It MUST be `bind(...)`
  with a `Function<ByteBuf, RegistryFriendlyByteBuf>` before use — the unbound form has no codec().
- `RegistryFriendlyByteBuf.decorator(RegistryAccess)` is the exact factory function to pass to
  `bind`. (`new RegistryFriendlyByteBuf(Unpooled.buffer(), access)` also exists but `decorator` is
  what `bind` wants — a `Function<ByteBuf,...>`, not a single buffer.)
- `ProtocolInfo.codec()` returns
  `StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>>` — `encode(buf, packet)` writes the
  leading varint id then the field payload. This is THE same codec the vanilla encoder uses, so the
  bytes are byte-for-byte what a client would receive (before compression/framing — which is also
  what we capture at `addBefore("encoder")`).
- Bind once per `RegistryAccess` and reuse the codec for all PLAY packets.

For CONFIGURATION-phase packets the analogue is `ConfigurationProtocols.CLIENTBOUND` (already a bound
`ProtocolInfo<ClientConfigurationPacketListener>` over `FriendlyByteBuf` — no registry access needed),
`.codec()` → `encode(buf, packet)`.

---

## Packets built successfully (Step 2)

| Packet | How constructed from live `ServerPlayer sp` / `ServerLevel level` | id | bytes |
|---|---|---:|---:|
| `ClientboundPlayerPositionPacket` | `ClientboundPlayerPositionPacket.of(sp.getId(), PositionMoveRotation.of(sp), Set.of())` | 65 | 62 |
| `ClientboundPlayerInfoUpdatePacket` | `new ...(EnumSet.of(ADD_PLAYER, UPDATE_LISTED), List.of(sp))` | 63 | 25 |
| `ClientboundLevelChunkWithLightPacket` | `new ...(level.getChunkAt(sp.blockPosition()), level.getLightEngine(), null, null)` | 39 | 7282 |
| `ClientboundLoginPacket` | `new ...(sp.getId(), server.isHardcore(), server.levelKeys(), maxPlayers, viewDistance, simulationDistance, false, true, false, sp.createCommonSpawnInfo(level), true)` | 43 | 113 |

None failed. Construction notes:
- **Position:** `PositionMoveRotation.of(Entity)` reads the live pos+rotation+delta; `ClientboundPlayerPositionPacket.of(int, PositionMoveRotation, Set<Relative>)` is the 1.21.5 factory (the packet is a record: `(int id, PositionMoveRotation change, Set<Relative> relatives)`). Empty relatives = absolute.
- **Player info:** the `(EnumSet<Action>, Collection<ServerPlayer>)` ctor builds the full ADD_PLAYER entry (uuid, profile w/ properties, listed flag) from the live player. (`createPlayerInitializing(Collection)` is the vanilla helper for the richer set; not needed for the minimal entry.)
- **Chunk:** the documented 4-arg ctor `(LevelChunk, LevelLightEngine, BitSet, BitSet)` works; trailing `null, null` = whole-chunk light (no partial bitsets). `level.getLightEngine()` (on `Level`) returns the `LevelLightEngine`. Largest packet by far (~7 KB) — chunks dominate snapshot size, confirming the chunk-cache dedup plan.
- **Login:** synthesized from `sp.createCommonSpawnInfo(level)` (the vanilla helper that packs dimension type/name, seed hash, gamemode, prev gamemode, debug/flat, last-death, portal cooldown, sea level) plus server-level fields (`isHardcore`, `levelKeys`, `getMaxPlayers/getViewDistance/getSimulationDistance`). The booleans (reducedDebugInfo / showDeathScreen / doLimitedCrafting / enforcesSecureChat) were passed sensible constants for the spike; for production mirror `PlayerList.placeNewPlayer` (gamerules + `server.enforceSecureProfile()`), but they do not affect serialize-ability.

---

## Registry / login strategy (Step 3)

**Login: SYNTHESIZE (no cache needed).** `ClientboundLoginPacket` constructed cleanly from live
state and serialized (id=43, 113 B). Building it from a fake connection / early capture is
unnecessary — direct construction from `ServerPlayer` + `MinecraftServer` works.

**Configuration-phase registry data: SYNTHESIZE via the vanilla configuration task (recommended).**
This spike serialized the PLAY packets; it did not synthesize the CONFIGURATION packets
(`ClientboundUpdateEnabledFeaturesPacket`, the per-registry `ClientboundRegistryDataPacket`s,
`ClientboundUpdateTagsPacket`). The clean path is confirmed available and is Arcade's approach:
- `net.minecraft.server.network.config.SynchronizeRegistriesTask` has
  `start(Consumer<Packet<?>>)` — driving it with our own consumer harvests the registry-data
  packets synchronously, no client ack required (it also exposes `handleResponse(...)` if we want to
  emulate the known-packs round-trip). Serialize each via `ConfigurationProtocols.CLIENTBOUND.codec()`.
- Enabled-features / tags packets are likewise buildable from `server` state
  (`FeatureFlags`, `server.registryAccess()` tags) and serialized with the CONFIGURATION codec.

**Early-capture fallback (Approach A) — not required, not pursued here.** Because login + registries
are session-invariant and synthesizable, we do not need to inject a capture handler before login to
record the real join sequence. (The prior research already judged early-capture insufficient for
mid-session starts and clips anyway.) If we later want byte-authentic registry data, the earliest
reliable Paper inject point remains channel-init; but synthesis covers our needs.

---

## `flashback:action/create_local_player` payload layout (Step 1, clean-room)

Read-only from Flashback `master` (no code copied):
- Writer: `record/Recorder.java#writeCreateLocalPlayer()`
- Reader: `playback/ReplayGamePacketHandler.java#handleCreateLocalPlayer(...)` (via
  `action/ActionCreateLocalPlayer` → `ReplayServer.handleCreateLocalPlayer`).

The payload is the action body written into a `RegistryFriendlyByteBuf`, in this EXACT order — and
the reader reads it back in the same order (verified both sides match):

| # | Field | Type / write call | Bytes |
|---|---|---|---|
| 1 | UUID | `writeUUID(uuid)` = two longs (msb, lsb) | 16 |
| 2 | x | `writeDouble` | 8 |
| 3 | y | `writeDouble` | 8 |
| 4 | z | `writeDouble` | 8 |
| 5 | xRot (yaw in writer var `xRot`) | `writeFloat` | 4 |
| 6 | yRot (pitch in writer var `yRot`) | `writeFloat` | 4 |
| 7 | yHeadRot | `writeFloat` | 4 |
| 8 | velocity x | `writeDouble` | 8 |
| 9 | velocity y | `writeDouble` | 8 |
| 10 | velocity z | `writeDouble` | 8 |
| 11 | GameProfile | `ByteBufCodecs.GAME_PROFILE.encode(buf, profile)` | var |
| 12 | gameModeId | `writeVarInt(gameModeId)` | var |

Notes:
- It is a **Flashback-synthetic action**, NOT a vanilla packet — there is no leading vanilla packet
  id; the action framing is Flashback's (`startAction(ActionCreateLocalPlayer.INSTANCE)` writes the
  registered action id, then this body, then `finishAction`). In our writer this is the
  `flashback:action/create_local_player` action with the above body as its payload bytes.
- **GameProfile** is serialized by Mojang's `ByteBufCodecs.GAME_PROFILE` stream codec: UUID +
  name(String) + properties (count-prefixed list of name/value/optional-signature). Build it as
  Flashback does: `new GameProfile(uuid, name, propertyMap)` using `sp.getGameProfile()` (NMS
  `ServerPlayer.getGameProfile()` returns the authlib `GameProfile` with skin properties).
- **Reader correspondence:** the reader builds
  `new ClientboundAddEntityPacket(localPlayerId, uuid, x, y, z, xRot, yRot, EntityType.PLAYER, 0, velocity, yHeadRot)`
  then reads GameProfile and `GameType.byId(varint)`. So field order above is authoritative.
- gameModeId is the vanilla `GameType` id (0=survival,1=creative,2=adventure,3=spectator); on the
  server use `sp.gameMode.getGameModeForPlayer().getId()`.

---

## Implications for R3a implementation

- **The serialization core is solved.** One bound PLAY codec (`GameProtocols.CLIENTBOUND_TEMPLATE
  .bind(RegistryFriendlyByteBuf.decorator(sp.registryAccess())).codec()`) serializes every PLAY
  packet to the same id+payload bytes we already record. A second bound codec from
  `ConfigurationProtocols.CLIENTBOUND` handles config-phase packets.
- **Minimal floor is reachable server-side:** Login (✓ serialized), create_local_player (layout
  known, all inputs available from `ServerPlayer`), position (✓), player-info (✓), ≥1 chunk (✓),
  next_tick (already implemented). Remaining work item, low-risk: drive `SynchronizeRegistriesTask`
  to emit + serialize the configuration registry packets.
- **Threading:** chunk + entity reads must run on the owning region thread (Folia) / main thread —
  the probe ran via the player's entity scheduler and worked. Reuse `PlatformScheduler`.
- **Version coupling:** the signatures used are 1.21.5 Mojmap and resolved at runtime under Paper's
  PluginRemapper. Pin per MC version: `GameProtocols.CLIENTBOUND_TEMPLATE`,
  `RegistryFriendlyByteBuf.decorator`, the 4-arg `ClientboundLevelChunkWithLightPacket` ctor,
  `ClientboundPlayerPositionPacket.of(int, PositionMoveRotation, Set)`, `createCommonSpawnInfo`,
  `ClientboundPlayerInfoUpdatePacket(EnumSet, Collection)`.

## Decode-replay smoke test — deemed impractical

MCProtocolLib decoding of raw clientbound PLAY `byte[]` in isolation requires a live session with
initialized protocol state (codec, compression, encryption, connection object). There is no public
API to feed raw bytes through a standalone decoder without a connected `Session`. Therefore a
test-scope decode-replay smoke test was not added. The structural `validateRenderable` check in
`FlashbackValidator` + the human P6 spot-check (loading the recording in the Flashback client) are
the verification gates for R3a.

---

## Reproduction

Temporary `SynthProbe` (probe) + `SynthSpikeIT` (harness test) produced the `[synth]` log above on a
real Paper 1.21.5 boot with an MCProtocolLib bot. Both were removed after the spike (this doc is the
artifact). The probe used the recipe verbatim; every packet logged `OK`.
