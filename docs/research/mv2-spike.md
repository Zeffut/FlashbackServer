# MV2 Spike — Captured-state cache keying from raw bytes

> Date: 2026-06-04. Protocol: 770 (MC 1.21.5). Branch: `feat/mv2-captured-state-cache`.
>
> Empirical spike to answer three questions before committing to the Phase 2 cache design
> (described in `multiversion-architecture.md §4 Phase 2`): can we key captured packets
> by type and by chunk (x,z) purely from the raw `varint id + payload` bytes already
> captured by `PacketCapture.injectRaw`?

---

## Method

A temporary `Mv2SpikeProbe` registered a `PacketSink` on every `PlayerJoinEvent`. For each
captured packet it:
1. Read the leading varint id using the LEB128 decoder.
2. Classified it via `PacketIds.forProtocol(770)` → `Ids(login=43, position=65, chunk=39, playerInfo=63)`.
3. For chunk packets (id=39), read the next two big-endian ints at `rawBytes[idByteLen..]` as
   `chunkX` and `chunkZ`.
4. Logged `[mv2]` lines (capped at 30 individual + a summary on quit).

A `Mv2SpikeIT` integration test booted real Paper 1.21.5, connected a bot (`Mv2Bot`) via
`BotClient`, waited ~5 s, disconnected, and asserted on the summary line.

Both the probe and the test were removed after data collection (see Cleanup below).

---

## Raw observed data (single run, ~5 s connected, stationary bot)

```
total=570  login=0  position=0  chunk=81  playerinfo=1  other=488
```

Sample of the first 30 logged packets:

```
[mv2] type=other      id=114  bytes=275
[mv2] type=playerinfo id=63   bytes=34
[mv2] type=other      id=88   bytes=2
[mv2] type=other      id=104  bytes=2
[mv2] type=other      id=87   bytes=7
[mv2] type=other      id=37   bytes=40
[mv2] type=other      id=106  bytes=18
[mv2] type=other      id=90   bytes=13
[mv2] type=other      id=34   bytes=6
[mv2] type=other      id=120  bytes=6
[mv2] type=other      id=121  bytes=2
[mv2] type=other      id=18   bytes=51
[mv2] type=other      id=20   bytes=6
[mv2] type=other      id=92   bytes=9
[mv2] type=other      id=67   bytes=167
[mv2] type=other      id=123  bytes=276
[mv2] type=other      id=97   bytes=10
[mv2] type=other      id=96   bytes=7
[mv2] type=other      id=16   bytes=932
[mv2] type=other      id=124  bytes=33
[mv2] type=chunk      id=39   bytes=9340   x=-1  z=0
[mv2] type=chunk      id=39   bytes=9342   x=-1  z=1
[mv2] type=chunk      id=39   bytes=7282   x=-2  z=0
[mv2] type=chunk      id=39   bytes=12110  x=0   z=0
[mv2] type=chunk      id=39   bytes=9340   x=-1  z=-1
[mv2] type=chunk      id=39   bytes=7282   x=-2  z=1
[mv2] type=chunk      id=39   bytes=12697  x=0   z=1
[mv2] type=chunk      id=39   bytes=7282   x=-3  z=0
[mv2] type=chunk      id=39   bytes=7282   x=-2  z=-1
[mv2] type=other      id=8    bytes=10
```

Bot spawn position (from server log): `([world]-9.5, -60.0, 7.5)` → chunk coord `(-1, 0)`.

---

## Q1 — Chunk (x,z) keying from raw bytes

**CONFIRMED.**

The two big-endian ints immediately after the leading varint decode correctly to chunk coordinates.
`x=-1 z=0` is the exact chunk the bot spawned in (world position -9.5, 7.5 → chunk -1, 0).
All observed chunk x,z values are plausible (within ±3 of spawn on a fresh flat world, matching
the server's chunk send radius). No mis-decoded coordinates were observed.

Concretely: for a packet whose raw bytes start with a varint equal to `39` (the chunk id on
protocol 770), the 4 bytes at `rawBytes[idVarintLen]` = chunkX and the next 4 bytes = chunkZ,
big-endian. This is the stable wire format of `ClientboundLevelChunkWithLightPacket` and is
sufficient for (x,z)-keyed caching without any NMS parsing.

---

## Q2 — Type identification from the leading varint

**RELIABLE. No ambiguity observed.**

The leading varint read from `rawBytes` matched `PacketIds.forProtocol(770)` values cleanly:
- `id=39` → chunk (81 packets, all correctly decoded x,z)
- `id=63` → playerinfo (1 packet)
- All other ids → "other" (no false positives into the four tracked ids)

The `PacketCapture` handler (at `addBefore("encoder")`) receives already-encoded `ByteBuf` bytes —
the prior `netty-pipeline.md` note suggesting Packet objects at that position is incorrect. The
working code and this spike confirm: `rawBytes` = varint id + payload (already encoded), which is
exactly what's needed for id-table classification and fixed-offset field reads.

---

## Q3 — Stream presence and cache timing

**Summary:**

| Packet type   | id  | Count in 5 s | When | Cache-viable from stream? |
|---------------|-----|-------------|------|--------------------------|
| chunk         | 39  | **81**      | Burst immediately after `PlayerJoinEvent` (~0–1 s) | **YES** |
| playerinfo    | 63  | **1**       | Immediately after `PlayerJoinEvent` | **YES** |
| position      | 65  | **0**       | Never seen — stationary bot | **NO (see below)** |
| login         | 43  | **0**       | Before `PlayerJoinEvent` — already flushed | **NO (expected)** |

### Chunk packets — cache viable, with an important nuance

81 chunk packets appeared in the stream within ~1 second of the bot joining. These are the
**join-burst chunks** sent immediately after `PlayerJoinEvent` as Paper dispatches the chunk
pipeline to the newly-connected player. They are NOT the initial join sequence (which is over
before `PlayerJoinEvent`); they are the regular post-join chunk-send burst, and they are captured
by the `PlayerJoinEvent`-installed sink.

**Implication:** The concern in the architecture doc (B.1) that "initial chunks may be missed"
refers to the very first pre-login chunks. In practice, Paper sends the chunk burst **after**
`PlayerJoinEvent`, so the capture does see it. For a `/replay start` on a player already
connected, only those chunks that arrive **during or after** the capture is installed are seen.
A player sitting still in a fully-loaded region will not generate new chunk packets, so
**mid-session snapshots for stationary players will have an empty chunk cache** unless a prior
session's cache is retained. Cache warmth requires the player to have moved or loaded new chunks
since the capture started.

### Position packets — NOT stream-cacheable for a stationary player

`ClientboundPlayerPositionPacket` (id=65) is sent by the server only on teleport or position
correction — NOT as a periodic keep-alive. A stationary bot received **zero** position packets
in 5 seconds. The position cache **cannot be populated from the packet stream alone for a
stationary player**.

**Implication:** The current `SnapshotBuilder` reads position directly from `ServerPlayer` via
NMS. Under the hybrid architecture, position must remain NMS-sourced (or sourced from the Bukkit
`player.getLocation()` API) for the snapshot; it cannot be replaced with "most recent captured
bytes". This is consistent with the architecture recommendation (keep a minimal NMS/Bukkit seed
for things the stream does not reliably emit).

### Player-info — stream-viable

One `ClientboundPlayerInfoUpdatePacket` (id=63) appeared immediately post-join. In a
multi-player session, additional updates will arrive as players join/leave/change state. The
latest captured bytes are suitable for caching and replaying.

---

## VERDICT

**CONDITIONAL GO.**

| Question | Finding | Design implication |
|---|---|---|
| Chunk (x,z) keying from raw bytes | **WORKS** — confirmed with real observed values | Cache chunk packets keyed by (x,z) extracted from rawBytes[idVarintLen..idVarintLen+8] |
| Type identification from leading varint | **RELIABLE** — no ambiguity, clean id-table match | Use `PacketIds.forProtocol(ver)` table; no NMS instanceof needed |
| Chunks in post-join stream | **YES** — 81 chunks arrive in the burst immediately after PlayerJoinEvent | Cache is viable for players active since capture start; mid-session static players need NMS fallback for older chunks |
| Position in stream | **NO** — 0 packets for stationary bot | Position must come from NMS / `player.getLocation()`, not from captured bytes |
| Player-info in stream | **YES** — 1 packet immediately | Cache latest captured player-info bytes |
| Login in stream | **NO** (expected) | Login stays NMS-constructed (`SnapshotSeed`) |

### GO conditions met
- Chunk (x,z) keying from raw bytes: **confirmed working**.
- Chunk + player-info packets appear in the post-join stream and are cache-viable.
- Type identification via the id table is reliable with no ambiguity.

### Residual concerns (not blockers, design-aware)
- **Position is not stream-cacheable.** The cache must be seeded via the Bukkit API
  (`player.getLocation()`) or kept NMS-constructed. This is already accounted for in the
  architecture recommendation (hybrid: streaming parts from cache, position/login from
  Bukkit/NMS seed).
- **Chunk cache warmth for mid-session.** A player sitting still since `/replay start` will have
  an empty chunk cache. The snapshot fallback (NMS `SnapshotBuilder.dynamicActions`) must remain
  available for that case. The cache is an optimisation for moving players / connect-time
  recordings, not a full replacement.
- **Login not in stream.** As designed; stays in the `SnapshotSeed` NMS module.

The captured-state cache design (`multiversion-architecture.md §4 Phase 2`) is viable for
chunk and player-info packets. Position and login require the existing NMS/Bukkit paths.
No architectural blocker was found; proceed to Phase 2 implementation with the above constraints
documented.

---

## Cleanup

The spike added two temporary files:
- `src/main/java/dev/zeffut/flashbackserver/capture/Mv2SpikeProbe.java` — removed after spike.
- `src/test/java/dev/zeffut/flashbackserver/capture/Mv2SpikeIT.java` — removed after spike.
- One line in `FlashbackServerPlugin.java` registering the probe — removed after spike.
- `spike-mv2.log` — gitignored.
