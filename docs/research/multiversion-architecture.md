# Multi-version architecture for Flashback Server

> Goal: support the 1.21.x line and future MC versions with minimal per-version maintenance.
> This is an architecture investigation grounded in the current 1.21.5-only code and the prior
> R3 research (`r3-initial-state.md`, `r3-spike.md`, `netty-pipeline.md`, `flashback-format.md`).
> No implementation here — a recommendation, the residual version-locked surface, a build/version
> strategy, and a phased decomposition.
>
> Date: 2026-06-04. Author: architecture research agent.

---

## 0. Where we are today (ground truth from the code)

The plugin is **already** built around what the brief calls "Approach B" in spirit — it does **not**
capture the join sequence; it **synthesises** the snapshot from the live `ServerPlayer` via NMS:

- **Version-agnostic** (pure Java / stable Bukkit API): `format/*` (container codec, action stream,
  varint), `clip/ClipBuffer`, `command/*`, `telemetry/*`, `record/FlashbackRecorder`/`ReplayFiles`/
  `TickClock`/`RecordingManager`, and the *byte-level* capture in `capture/PacketCapture`
  (it copies the outbound message's bytes and fans them to sinks — see the wrinkle in §3.2).
- **Version-locked** (NMS, compiled against the 1.21.5 dev bundle):
  - `capture/ChannelAccess` — `CraftPlayer.getHandle().connection.connection.channel`.
  - `snapshot/PacketSerializer` — `GameProtocols.CLIENTBOUND_TEMPLATE.bind(...)` / `ConfigurationProtocols.CLIENTBOUND`.
  - `snapshot/SnapshotBuilder` — constructs `ClientboundLoginPacket`, `ClientboundPlayerPositionPacket`,
    `ClientboundPlayerInfoUpdatePacket`, `ClientboundLevelChunkWithLightPacket`, the config packets
    via `SynchronizeRegistriesTask`, plus `FeatureFlags`/`TagNetworkSerialization`.
  - `snapshot/CreateLocalPlayerAction` — pulls UUID/pos/rot/velocity/profile/gamemode off `ServerPlayer`
    and encodes the profile with `ByteBufCodecs.GAME_PROFILE`.
  - `snapshot/McVersions` — `SharedConstants.getProtocolVersion()` / data version.
  - `verify/ReplayDecodeVerifier` — decodes via the real codecs.
  - `format/FlashbackValidator.validateRenderable` — **hardcoded** 1.21.5 PLAY ids
    (login=43, position=65, chunk=39, player-info=63).

So the question "A vs B" is really: **do we keep constructing packets via NMS (status quo, version-locked
construction) and version that NMS surface (Approach A-style adapters), or do we re-found the snapshot
on captured real bytes so the NMS surface largely disappears (the brief's Approach B)?**

---

## 1. Approach A — per-version NMS adapters

A `NmsAdapter` interface (channel access, packet encode/decode, snapshot construction) with one
implementation per MC version (`v1_21_5`, `v1_21_8`, …), a multi-module paperweight-userdev build
(each module against its own dev bundle), runtime selection by detected server version, all adapters
shaded into one jar.

### Feasibility
Feasible and well-understood. It is the *literal* generalisation of the current code: lift everything
in `snapshot/*`, `capture/ChannelAccess`, `verify/*`, and the id table behind an interface, and provide
N implementations. The interface is small (channel, encode-game, encode-config, build-snapshot,
ids, version probe).

### Gradle complexity (paperweight-userdev multi-module)
This is the real cost. paperweight-userdev binds a module to **one** `paperDevBundle("<ver>")` and runs
a `reobfJar` per module. To bundle adapters for K versions you need:
- a Gradle subproject per version, each applying `io.papermc.paperweight.userdev` with its own dev bundle;
- a `:core` project (version-agnostic) that the adapters depend on but that itself has **no** paperweight;
- a `:bootstrap`/`:plugin` project that depends on `:core` + every adapter's **reobf** output and shades
  them with `shadow`. Each adapter must be remapped against the right mappings *before* shading; mixing
  Mojang-mapped classes from two MC versions in one classpath only works because each adapter is
  reobfuscated to that version's runtime names and the wrong-version classes are simply never loaded.
- Class-name collisions: every adapter references `net.minecraft.network.protocol.game.ClientboundLoginPacket`
  etc. After reobf these are *runtime* names that differ across versions only when Mojang changes them;
  within 1.21.x most NMS names are stable, so the adapters compile against near-identical signatures but
  must each be built against their own bundle to be safe. You cannot compile one adapter against two
  bundles — hence one module per version, even when the code is byte-identical.

This is buildable but heavy: every CI run downloads/decompiles K dev bundles, runs K userdev setups and
K reobf passes. Build time and contributor friction scale linearly with supported versions.

### Jar size
Each adapter's own bytecode is tiny (a few KB of glue). NMS itself is `compileOnly`/provided by the
server, so it is **not** shaded. Net jar growth is negligible (KBs per version). Jar size is **not** a
real objection to A.

### Ongoing maintenance cost — the decisive objection
**Every new MC release needs a new adapter module**, even when nothing changed, because the dev bundle
coordinate is pinned per module. In practice each MC release also moves at least one signature the
snapshot construction touches (the brief already lists the fragile set: the 4-arg
`ClientboundLevelChunkWithLightPacket` ctor, `ClientboundPlayerPositionPacket.of(int, PositionMoveRotation, Set)`,
`createCommonSpawnInfo`, the `ClientboundPlayerInfoUpdatePacket(EnumSet, Collection)` ctor,
`SynchronizeRegistriesTask` shape, `TagNetworkSerialization`). So A is a *recurring, mandatory* per-release
task with a real chance of breakage each time. This is exactly the maintenance burden we are trying to avoid.

### Is this how real multi-version Paper plugins do it?
Yes — for plugins that must **construct or read** NMS objects (ProtocolLib-class interception, NPC/packet
libraries, WorldEdit-style world access), the "abstract NMS handler + one impl per version, runtime-selected,
shaded" pattern is the industry norm (ProtocolLib's `MinecraftReflection`/`WrappedX`, PacketEvents,
Citizens, FastAsyncWorldEdit's adapters, the old NMS-handler pattern). It works, but every one of those
projects carries a per-version maintenance tax and a lag behind new MC releases — precisely the symptom
we want to escape.

### Lighter variant — reflection-based single adapter (no paperweight)
Instead of N compiled modules, write **one** adapter that resolves everything via reflection / method
handles against the server at runtime, compiled against **no** dev bundle (only Bukkit API). This is how
ProtocolLib/PacketEvents avoid a module per version for the *channel* and for *field access*. It removes
the multi-module build entirely. But it is a poor fit for the **construction** half: building a
`ClientboundLoginPacket` reflectively means hand-encoding ctor arg lists and registry-aware buffers
per version anyway — you have merely moved the per-version table from compiled code into reflection
strings, with worse safety (no compile checks) and more runtime fragility. Reflection is great for
**reading stable handles** (channel, registry access) and **invoking the codec**; it is bad for
**re-implementing packet construction**. This observation is what makes Approach B attractive: if we
stop *constructing* packets, the reflection surface collapses to just the stable handles.

**Verdict on A:** feasible, conventional, low jar cost — but it locks us into a mandatory per-release
adapter and keeps the entire snapshot-construction surface version-coupled forever. Acceptable as a
fallback, not as the target.

---

## 2. Approach B — version-agnostic snapshot from CAPTURED bytes

Stop constructing snapshot packets via NMS. Instead **capture the real outbound packet bytes the server
already sends** and re-assemble them into the snapshot. The bytes are version-correct by construction.

Concretely:
- Inject the capture handler **early** (login/configuration phase) so we capture the real
  CONFIGURATION packets (registries/tags/enabled-features) + `ClientboundLoginPacket` + initial chunk
  packets as raw bytes.
- Maintain a per-player **state cache** of raw captured bytes keyed by meaning: the login packet, the
  config-phase packets (captured once), the **latest** `ClientboundLevelChunkWithLightPacket` per chunk
  coord (captured as chunks stream while the player moves), latest position, latest player-info, etc.
- A snapshot at any moment (recording start OR clip keyframe) = assemble cached config + login +
  `create_local_player` + latest position + player-info + the cached chunk packets around the player.
  All raw bytes → version-agnostic.

### The hard questions, answered

#### B.1 — Earliest reliable injection point on Paper for config-phase + login bytes

Findings, grounded in the code and Paper internals:

- **`PlayerJoinEvent` is too late.** By join the configuration phase is over and the
  `ClientboundLoginPacket` + initial chunks have already been written and flushed. `netty-pipeline.md`
  observed the pipeline *at `PlayerJoinEvent`* with the full PLAY pipeline already assembled — i.e. the
  one-shot connect packets are gone. The current `PacketCapture.injectRaw` is called from
  `RecordingManager.start()` (command/join time) → it can never see those packets. This is the same
  "Approach A insufficient for mid-session" conclusion from `r3-initial-state.md §Q3`.
- **`PlayerLoginEvent` / `AsyncPlayerPreLoginEvent`** fire during LOGIN, before CONFIGURATION and PLAY.
  In principle early enough — but at `AsyncPlayerPreLoginEvent` there is no `Player`/`CraftPlayer` yet
  (only a profile), and at `PlayerLoginEvent` the `ServerPlayer`/channel handle is only just being wired.
  Using these reliably to grab the channel and install a handler *before the config packets are written*
  is racy on Paper and not a documented contract.
- **The genuine earliest point is the Netty `ChannelInitializer`** on the server's child pipeline
  (NMS `ServerConnectionListener`'s `ServerBootstrap` `childHandler` / the `connection.channels` list).
  Injecting there means every new connection gets our handler before the vanilla login/config handlers
  run. This is exactly how **ProtocolLib** (`TemporaryPlayerFactory` + `NettyChannelInjector` wrapping
  the server channel's `childHandler`) and **PacketEvents** (`ServerConnectionInitializer`) capture
  connect-time packets. **It requires NMS/reflection into the server connection list and is the single
  most version-fragile and Paper-build-fragile hook we would own.**
- **How other server-side replay tools get connect-time packets:** they **don't capture them off the
  socket at all.** Per `r3-initial-state.md §Q2`, Arcade/ServerReplay (`arcade-replay`,
  `RejoinConnection` + `RejoinConfigurationPacketListener`) **re-run the server's own join pipeline
  against a fake in-memory `Connection`** and record the output synchronously, with no client and no
  socket capture. That is a *construction* approach (NMS-heavy), not a capture approach. So the
  precedent for "get the join sequence" is re-running the pipeline, not early socket injection.

**Conclusion for B.1:** early injection that reliably captures the CONFIGURATION + login bytes is only
achievable via a server-channel `ChannelInitializer` (ProtocolLib-style), which is **itself NMS- and
build-coupled** and re-introduces the fragility B was supposed to remove. And it still only helps
**connect-time** recordings — for `/replay start` mid-session and for clips, those one-shot packets were
sent long ago and are gone (the decisive limitation from `r3-initial-state.md §Q3/Q4`). **Early capture
cannot be the foundation; it can only be an optional optimisation for connect-time recordings.**

#### B.2 — Identifying packet TYPES from raw bytes without full NMS

The brief asks how to recognise "this is a chunk / login / position packet" and key chunks by (x,z),
across versions, without hardcoding ids. Two sub-findings:

**(a) Tapping the `Packet<?>` OBJECT vs the encoded `ByteBuf` — and a discrepancy in our own docs.**
There is a contradiction between two prior spikes that is load-bearing here and must be resolved before
relying on B:
- `netty-pipeline.md` reasons (correctly, by pipeline order) that **outbound** messages travel
  TAIL→HEAD as `packet_handler → unbundler → hackfix → encoder → compress → prepender`. The **encoder**
  is what turns a `Packet<?>` into a `ByteBuf`. Therefore a handler added with `addBefore("encoder")`
  sits **upstream of the encoder in the outbound direction**, where the message is still a
  **`Packet<?>` object**. That doc explicitly lists observed `Clientbound*Packet` objects there.
- `flashback-format.md` (the `RawCaptureSpikeIT`) claims that at the *same* `addBefore("encoder")`
  point it observed `PooledUnsafeDirectByteBuf` (already-encoded bytes), and `PacketCapture.java`'s
  handler is written for `msg instanceof ByteBuf`.

  These cannot both be literally true at the identical handler position. The most likely reconciliation:
  Netty's `addBefore(name, ...)` inserts the handler **before** the named handler in the **pipeline
  list order** (which is the *inbound* order, HEAD→TAIL); for an **outbound** write the data flows
  TAIL→HEAD, so a handler placed "before encoder" in list order is actually reached **after** the
  encoder on the way out — i.e. it sees the **ByteBuf**, matching `flashback-format.md` and the working
  capture code. The `netty-pipeline.md` table is describing the *position*, not the runtime message
  type at that position, and its "we get Packet objects" note is the inaccurate part.

  **Why this matters for B:** Approach B's nicest version of B.2 — "tap the `Packet<?>` object so we can
  do `instanceof ClientboundLevelChunkWithLightPacket` and read `getX()/getZ()` for keying, while still
  storing the encoded bytes" — requires being **upstream of the encoder on the outbound path**, i.e.
  `addBefore("unbundler")` / `addBefore("hackfix")` (or wherever sits TAIL-ward of `encoder`), **not**
  `addBefore("encoder")`. That is achievable, but: (i) it makes us depend on `instanceof` against
  versioned NMS classes — **re-introducing the NMS coupling B set out to avoid**, just for *keying*; and
  (ii) it means capturing both the object (for the key) and separately serialising/observing the bytes.
  So "tap the Packet object" defeats the premise of B (no NMS).

**(b) A per-version packet-ID table (leading varint).** The packet id is the leading varint of the
encoded bytes (already used by `FlashbackValidator.readLeadingVarInt`). To classify by id we need a
mapping {version → {login id, chunk id, position id, player-info id, config-phase ids}}. This is **far
lighter than full NMS adapters**: a handful of ints per version in a data table, not compiled modules.
But two problems remain:
  - We still need **per-version id values** (the brief's premise "without hardcoding ids" is not fully
    escapable — the ids genuinely differ per protocol version; the best we can do is move them from
    `static final int` in code to a versioned data table, optionally sourced from a public protocol-id
    dataset like the wiki/PrismarineJS data per protocol version).
  - For **keying chunks by (x,z)** we must parse *inside* the chunk packet payload. The chunk packet
    begins (after the id varint) with `int chunkX, int chunkZ` (big-endian ints, stable layout across
    1.21.x) — so x/z keying is decodable from raw bytes with a tiny, low-risk reader **without NMS**,
    given we know it is the chunk packet (from the id table). The deeper payload (heightmaps, sections,
    light) we never need to parse — we store it verbatim. This is the cleanest version-coupled point: a
    small id table + a 8-byte field read at a known offset.

**Conclusion for B.2:** the cleanest non-NMS classification is **(b) a versioned packet-id table + a
fixed-offset (x,z) read for chunk keying**. It is genuinely lighter than A. But it is **still
version-coupled** (the id table must track every protocol version), and it cannot be auto-derived at
runtime without NMS or an external protocol dataset. Option (a) "tap the Packet object" is cleaner to
*write* but re-introduces NMS `instanceof`, so it is not actually version-agnostic.

#### B.3 — `create_local_player` from the Bukkit API (version-agnostic?)

`create_local_player` is a Flashback-synthetic action (not a wire packet). Its fields (from
`r3-spike.md` / `CreateLocalPlayerAction`): UUID, x/y/z, xRot/yRot/yHeadRot, velocity x/y/z, GameProfile,
gameModeId. Can these come from stable Bukkit API instead of `ServerPlayer`?

- UUID — `player.getUniqueId()` ✅
- x/y/z, yaw/pitch — `player.getLocation()` ✅
- velocity — `player.getVelocity()` ✅ (Bukkit `Vector`)
- gameMode — `player.getGameMode()` → map `GameMode` enum to the vanilla id (SURVIVAL=0…SPECTATOR=3),
  a stable 4-value mapping ✅
- **yHeadRot** — Bukkit exposes body yaw via `getLocation().getYaw()`; there is no separate *head* yaw
  in the stable API for the player's own entity. For the local player, head-yaw == body-yaw is an
  acceptable approximation (the local player faces where they look). ✅ (good enough)
- **GameProfile** — this is the only catch. The action encodes an **authlib `GameProfile`** (name +
  properties incl. the textures signature) using `ByteBufCodecs.GAME_PROFILE` (NMS). Bukkit's modern
  API exposes `player.getPlayerProfile()` (`com.destroystokyo.paper.profile.PlayerProfile` /
  `org.bukkit.profile.PlayerProfile`) with name, UUID, and `getProperties()` (`ProfileProperty`:
  name/value/signature). So the *data* is available version-agnostically; only the *wire encoding* of
  `{uuid, name, [name,value,opt signature]…}` would need a tiny hand-rolled serialiser (it is a stable,
  trivial layout — UUID, string, varint-count, then per property string/string/optional-string). That
  serialiser is ~15 lines of pure Java, version-agnostic.

**Conclusion for B.3:** ✅ `create_local_player` can be built entirely from the stable Bukkit API plus a
~15-line pure-Java GameProfile serialiser. **No NMS required.** This is a genuine win for B and is worth
doing **regardless** of which overall approach we pick.

#### B.4 — Minimal version-locked surface remaining under B, and the single-jar question

If B's snapshot is assembled from captured bytes + Bukkit-API `create_local_player`, the residual
version-locked surface is:
1. **`ChannelAccess`** — getting the Netty `Channel` for a player. Doable via reflection against stable
   field names (`CraftPlayer.getHandle().connection.connection.channel`) → **reflection, no paperweight**.
   ProtocolLib/PacketEvents do exactly this across many versions from one jar.
2. **A packet-id table** (login/chunk/position/player-info/config ids per protocol version) +
   the fixed-offset chunk (x,z) read — **data, no paperweight**.
3. **The config/login/initial-chunk acquisition problem** (B.1) — either early server-channel injection
   (ProtocolLib-style reflection, fragile but no paperweight) **or** we concede we cannot get those bytes
   mid-session and must fall back to NMS construction for them.

That last point is the crux. **B works cleanly only for state we can observe streaming on the wire while
we are attached** (chunks as the player moves, position, player-info — all sent repeatedly). It works
**poorly for the one-shot connect packets** (config registries + login), which:
- are sent once, before we can attach for a mid-session `/replay start`, and
- are *required* for the renderable floor (`r3-initial-state.md §Q1`).

So under B you can be version-agnostic for the **streaming** part but you still need the **one-shot
config+login** part. Options for that part, none fully satisfying:
- **(i) Early server-channel injection + cache config/login per connection from join onward**
  (the brief's literal B): version-agnostic *bytes*, but the injection hook is NMS/reflection and
  build-fragile, AND it only covers players whose connection we attached to at connect — a server
  restart mid-session loses the cache, and it adds always-on per-connection capture overhead.
- **(ii) Keep NMS construction for just config+login** (hybrid): the only version-locked *construction*
  left is `SynchronizeRegistriesTask` + `ClientboundLoginPacket`, which is small but still paperweight.

**Single no-paperweight jar spanning 1.21.x + future?** **Partially.** A single reflection-only jar can
realistically span versions for: channel access, byte capture, chunk/position/player-info caching and
keying (via id table), and `create_local_player`. It **cannot** robustly produce the **config-registry +
login** bytes mid-session without either (a) NMS construction (paperweight, version-locked) or (b) the
fragile early-injection cache. **So a pure no-paperweight single jar that spans 1.21.x+future AND
produces fully renderable recordings from arbitrary mid-session starts is not achievable today** — the
config/login floor is the blocker.

#### B.5 — Decode-verify across versions

`ReplayDecodeVerifier` currently decodes through the real NMS codecs (version-locked, and only works for
the version the jar was built against). Under B:
- **It cannot stay full-fidelity and version-agnostic.** Decoding a packet's *fields* requires the
  version's codec (NMS) — there is no version-free way to fully decode arbitrary clientbound packets.
  (`r3-spike.md` already found standalone MCProtocolLib decode impractical without a live session, and
  it would also be pinned to one protocol version.)
- **It can become a version-agnostic *structural* check:** leading-varint sanity (id present, plausible
  range), per-action `payload_size` framing correctness, presence/order of the required action
  identifiers, chunk (x,z) parse sanity, and a trailing `next_tick`. This is exactly the
  `validateRenderable` direction but **de-hardcoded** (id table instead of literals) and is fully
  autonomous and cross-version. Full codec decode stays a **best-effort, version-matched** extra check
  that only runs when the jar's build version matches the recording's `protocol_version`.

**Conclusion for B.5:** verification splits into a **version-agnostic structural tier** (always on) and a
**version-matched codec tier** (best-effort, only when versions align). This is strictly better than
today and is worth doing regardless of A/B.

---

## 3. Recommendation — **Hybrid (B-flavoured), staged; A as the construction fallback**

**Recommended target architecture:**

> A single **core jar with no paperweight** (stable Bukkit API + reflection) handling everything that can
> be made version-agnostic — channel access, byte capture, streaming-state caching (chunks keyed by
> (x,z), position, player-info), `create_local_player` from Bukkit API, the whole `format/*` writer, and a
> version-agnostic **structural** verifier driven by a small **versioned id table** (data, not code).
> Plus a **thin, narrowly-scoped NMS construction module** (paperweight) responsible for the **only** part
> that genuinely needs it: producing the **CONFIGURATION registry/tags/features + `ClientboundLoginPacket`**
> bytes for mid-session snapshots, behind a tiny `SnapshotSeed` interface. That module is the **single**
> per-version maintenance unit — and it is small (config+login construction only), not the whole snapshot.

Rationale:
- B genuinely removes NMS from the **large, frequently-touched** surface (chunks, position, player-info,
  create_local_player, verification structure) — that is where most fidelity work and most version churn
  live. Those become version-agnostic.
- B **cannot** remove NMS from the **config+login floor** for mid-session starts (B.1/B.4). Pretending it
  can (via early injection) trades a small NMS construction surface for a *more* fragile NMS/reflection
  injection surface plus always-on capture cost and a restart-loses-cache failure mode. Net worse.
- Therefore keep a **minimal** NMS module for exactly config+login (the current `SynchronizeRegistriesTask`
  + `ClientboundLoginPacket` logic), and make *everything else* version-agnostic. This is the hybrid: the
  per-version tax shrinks from "the whole snapshot builder + serializer + verifier + id table" to "one
  small config/login seed + the id-table data".
- If even that minimal module must support many versions, wrap it with the **Approach A multi-module
  pattern** — but applied to a *tiny* surface, so A's maintenance cost is paid on ~30 lines, not on the
  whole feature.

**Why not pure A:** mandatory per-release adapter for the *entire* snapshot construction surface; every
release risks breaking chunk/position/player-info construction signatures; keeps verification version-locked.

**Why not pure B:** the config+login floor is unobtainable version-agnostically for mid-session starts;
early injection is more fragile than the small NMS construction it would replace and adds runtime cost.

### Residual version-locked surface under the recommendation
- **Compiled NMS (paperweight), small & stable-ish:** `SnapshotSeed` = construct config-phase
  registry/tags/features + `ClientboundLoginPacket`, encode via the bound codec. (Today: the config half
  of `SnapshotBuilder` + `PacketSerializer`.) This is the one mandatory per-version module.
- **Reflection (no paperweight):** `ChannelAccess` (channel field path).
- **Data (no paperweight):** the per-protocol-version **packet-id table** (login/chunk/position/
  player-info/config ids) used by the writer's keying and by the structural verifier.
- **Best-effort, version-matched (paperweight, optional):** full-codec `ReplayDecodeVerifier`, only runs
  when build version == recording version.

Everything else — `format/*`, capture bytes, chunk/position/player-info caching & keying,
`create_local_player`, structural verification, clip ring buffer, commands, telemetry — is
**version-agnostic**.

### Single-jar vs matrix
- **Recommended shipping:** **one jar** that bundles the core (no paperweight) + the `SnapshotSeed` NMS
  modules for each *supported* version (Approach A pattern, but only for the tiny seed). Runtime-select
  the seed by detected version; if no seed matches, the plugin still **records** (capture + streaming
  snapshot) and only the config/login floor is degraded/absent (graceful: recording works, renderability
  may need a matching seed). This gives forward-compat: a brand-new MC version still records on day one;
  full renderability lands when a seed for it ships.
- **Version detection:** `Bukkit.getMinecraftVersion()` (Paper API, returns e.g. `"1.21.5"`) or
  `Bukkit.getServer().getVersion()` parsing; do **not** rely on `SharedConstants` (NMS) in the core.
  Map the detected version → protocol id-table entry → seed module.
- **Supported versions:** target the **current 1.21.x** line first (1.21.5 today; add seeds for new
  1.21.x as they ship). The id-table + core span the whole line from one build; only a new seed module
  per version is the marginal cost — and even without it, recording still works.

---

## 4. Phased decomposition from the current 1.21.5-only code

**Phase 0 — De-risk and de-couple verification (no behaviour change).**
- Replace the hardcoded ids in `FlashbackValidator.validateRenderable` with a `PacketIds` lookup keyed by
  the recording's `protocol_version` (data table; seed it with the known 1.21.5 values). Cheap, immediately
  reduces the version-locked surface and unblocks cross-version structural checks. Low risk.

**Phase 1 — Make `create_local_player` version-agnostic.**
- Reimplement `CreateLocalPlayerAction` on the Bukkit API (`getUniqueId`/`getLocation`/`getVelocity`/
  `getGameMode`/`getPlayerProfile`) + a ~15-line pure-Java GameProfile serialiser. Remove its NMS imports.
  Verify byte-identical output against the current NMS path on 1.21.5 (golden-bytes test). Low risk, pure win.

**Phase 2 — Split `SnapshotBuilder` into version-agnostic vs `SnapshotSeed`.**
- Move chunk/position/player-info **acquisition** toward captured bytes: the writer already captures the
  live `ClientboundLevelChunkWithLightPacket`/position/player-info bytes on the wire — cache the latest per
  chunk coord (key via id-table + fixed-offset (x,z) read) and latest position/player-info, and assemble
  the snapshot's streaming part from the cache instead of constructing them via NMS.
- Keep **only** config-registry/tags/features + `ClientboundLoginPacket` construction behind a
  `SnapshotSeed` interface, implemented by a thin NMS module. This is the residual paperweight surface.
- Risk: medium. The keying/offset reads and the "is the cache warm enough at snapshot time" logic are the
  new work. Mitigate with the structural verifier from Phase 0 and the existing renderable-recording ITs.

**Phase 3 — Multi-module build for the seed (Approach A pattern, tiny surface).**
- Convert to Gradle subprojects: `:core` (no paperweight, the bulk of the plugin), `:seed-v1_21_5`
  (paperweight, the `SnapshotSeed`), `:plugin` (shade core + all seeds). Runtime-select the seed by
  `Bukkit.getMinecraftVersion()`. Add a new `:seed-vX` per supported version.
- Risk: build complexity (the genuine cost of A), but confined to a tiny module so each new version is a
  ~30-line module + an id-table row, not a full re-port.

**Phase 4 — (Optional) connect-time early-capture optimisation.**
- For recordings that start at connect, optionally add a ProtocolLib-style server-channel injector to cache
  the *authentic* config/login bytes, bypassing the seed for those sessions. Strictly an optimisation;
  the seed remains the correctness baseline for mid-session/clip. Defer until the hybrid is stable; only do
  it if byte-authentic registries prove necessary in P6 visual checks.

**Phase 5 — Tiered verification.**
- `ReplayDecodeVerifier` becomes two tiers: always-on structural (version-agnostic, id-table driven) +
  best-effort full-codec (only when build version == recording `protocol_version`). Wire the structural
  tier into CI as the cross-version gate.

---

## 5. Honest risk / effort / maintenance summary

| Approach | Effort to reach | Per-release maintenance | Forward-compat (new MC w/o work) | Key risk |
|---|---|---|---|---|
| **A (full NMS adapters)** | Medium (mechanical) | **High** — full snapshot module per release | None (won't load until adapter exists) | Whole construction surface breaks per release; heavy K-bundle build |
| **B (pure captured bytes, no paperweight)** | High | Low for streaming parts | Records on day one… | **Config/login floor unobtainable mid-session; early-injection hook more fragile than the NMS it replaces; our own Packet-vs-ByteBuf doc discrepancy (§B.2a) must be resolved first** |
| **Hybrid (recommended)** | Medium-High, staged | **Low** — tiny `SnapshotSeed` per release + 1 id-table row | **Records day-one even without a seed; full renderability when seed ships** | Cache-warmth/keying correctness; resolving the §B.2a pipeline-message-type discrepancy; small NMS seed still needs per-version validation |

**Biggest risks to call out:**
1. **The §B.2a contradiction in our own prior docs** (`netty-pipeline.md` says we see `Packet<?>` at
   `addBefore("encoder")`; `flashback-format.md`/`PacketCapture` say `ByteBuf`). The working code treats it
   as `ByteBuf`, which is consistent with outbound TAIL→HEAD flow. **This must be re-verified empirically
   before building B's keying**, because whether we can cheaply `instanceof`-key chunks (Packet object) or
   must id-table+offset-key them (ByteBuf) hinges on it. The current evidence favours **ByteBuf**, hence
   the id-table approach in the recommendation.
2. **The config/login floor** is the hard architectural boundary: it is the one piece that resists
   version-agnosticism for mid-session/clip snapshots. The recommendation accepts a small NMS seed for it
   rather than fighting it with a fragile early-injection hook.
3. **Forward compatibility is partial, not total:** a brand-new MC version will **record** with the
   no-paperweight core, but full renderability needs a matching `SnapshotSeed` + id-table row. That is the
   irreducible per-version tax — minimised, not eliminated.
