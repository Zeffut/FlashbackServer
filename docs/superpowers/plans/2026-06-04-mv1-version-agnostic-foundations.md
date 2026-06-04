# MV1 — Version-Agnostic Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use `- [ ]`.
> Part of the multi-version effort (`docs/research/multiversion-architecture.md`).

**Goal:** Remove two pieces of 1.21.5-hardcoding so they become version-aware/version-agnostic:
(1) the validator's packet IDs → a protocol-version-keyed table read from the replay's own metadata;
(2) the `create_local_player` snapshot action → built from the **Bukkit API** (no NMS), with a manual
GameProfile wire serialization. Both are low-risk, unit-testable refactors that shrink the version-locked
surface.

---

### Task 1: `PacketIds` table + version-aware `validateRenderable`

**Files:** `format/PacketIds.java` (new), `format/FlashbackValidator.java` (modify), tests.

- [ ] **Step 1: `PacketIds`** — a tiny lookup keyed by **protocol version** (the replay's metadata stores
  `protocol_version`). Holds the clientbound PLAY packet ids we check: login, position, chunk, player-info.
  ```java
  package dev.zeffut.flashbackserver.format;
  import java.util.Map;
  import java.util.Optional;
  public final class PacketIds {
      public record Ids(int login, int position, int chunk, int playerInfo) {}
      // protocol version -> ids. 770 = MC 1.21.5 (from the F1 spike). Add rows as versions are supported.
      private static final Map<Integer, Ids> TABLE = Map.of(
          770, new Ids(43, 65, 39, 63)
      );
      private PacketIds() {}
      public static Optional<Ids> forProtocol(int protocolVersion) { return Optional.ofNullable(TABLE.get(protocolVersion)); }
  }
  ```
- [ ] **Step 2: `validateRenderable`** reads `meta.protocolVersion`, looks up `PacketIds.forProtocol(...)`:
  - If present → assert the snapshot contains a `configuration_packet`, a `create_local_player`, and
    game_packets whose leading varints == login/position/chunk ids (player-info nice-to-have), as today but
    using the looked-up ids instead of hardcoded constants.
  - If ABSENT (unknown protocol) → degrade gracefully: assert only the version-agnostic floor (≥1
    `configuration_packet`, a `create_local_player`, and ≥1 `game_packet`), and add an informational problem
    `"renderable check limited: unknown protocol <n> (packet ids not in table)"` that does NOT by itself mark
    invalid (valid stays true if the agnostic floor is met). Document this in the method Javadoc.
  Update `FlashbackValidatorRenderableTest` fixtures: they build replays with `meta.protocolVersion = 770`
  (so the id checks run) — set that on the fixtures' FlashbackMeta. Add one test with an unknown protocol
  (e.g. 999) asserting the graceful-degrade path returns valid when the agnostic floor is present.
- [ ] **Step 3: Build + test.** `./gradlew build` green; validator tests pass.
```bash
git add src/main/java/dev/zeffut/flashbackserver/format/ src/test/java/dev/zeffut/flashbackserver/format/
git -c commit.gpgsign=false commit -m "feat: version-keyed PacketIds table; validateRenderable uses the replay's protocol version"
```

---

### Task 2: `create_local_player` from the Bukkit API (no NMS)

**Files:** `snapshot/CreateLocalPlayerAction.java` (modify), `snapshot/SnapshotBuilder.java` (modify), test.

- [ ] **Step 1:** Re-implement `CreateLocalPlayerAction` to build its payload from the **Bukkit `Player`**
  (no `ServerPlayer`/NMS), keeping the EXACT documented byte layout (UUID 2 longs, x/y/z doubles,
  xRot/yRot/yHeadRot floats, velocity x/y/z doubles, GameProfile, gameModeId varint):
  - UUID ← `player.getUniqueId()`.
  - x/y/z ← `player.getLocation().getX()/getY()/getZ()`.
  - xRot (pitch) ← `getLocation().getPitch()`; yRot (yaw) ← `getLocation().getYaw()`; yHeadRot ← `getLocation().getYaw()`.
  - velocity ← `player.getVelocity().getX()/getY()/getZ()`.
  - GameProfile ← serialize MANUALLY in Minecraft wire format from `player.getPlayerProfile()`
    (`org.bukkit.profile.PlayerProfile` or Paper's): `writeUUID(profile id, or player uuid if null)` +
    `VarCodec.writeString(name)` + properties: `varint count`, then per property
    `VarCodec.writeString(propName)` + `VarCodec.writeString(propValue)` + `boolean hasSignature` (1 byte:
    1/0) + if present `VarCodec.writeString(signature)`. (This matches MC's `ByteBufCodecs.GAME_PROFILE` /
    `GameProfile` wire format; it is stable across versions.) Use the player's profile properties (textures, etc.).
  - gameModeId ← map `player.getGameMode()` → vanilla id (SURVIVAL=0, CREATIVE=1, ADVENTURE=2, SPECTATOR=3) via varint.
  Keep `IDENTIFIER = "flashback:action/create_local_player"`. Expose a `static byte[] payload(Player player)`.
  Keep a primitive-args helper for unit testing the layout (UUID/positions/rotations/velocity/gameMode +
  a profile descriptor) so the byte order stays locked.
- [ ] **Step 2:** `SnapshotBuilder.dynamicActions` calls `CreateLocalPlayerAction.payload(player)` (the Bukkit
  Player it already has) instead of the ServerPlayer overload. (Login/position/player-info/chunks still use
  NMS for now — those move in MV2/MV3; this task only de-NMSes create_local_player.)
- [ ] **Step 3:** Update `CreateLocalPlayerActionTest` to assert the byte layout via the primitive/profile
  helper (UUID 16 bytes, 3 doubles, 3 floats, 3 doubles, then a known GameProfile serialization for a fixed
  profile, then gameMode varint). No server needed.
- [ ] **Step 4: Build + verify.** `./gradlew build` green. `./gradlew test integrationTest` → ALL green
  (RenderableRecordingIT/SnapshotFidelityIT/RenderableClipIT still pass — create_local_player still present &
  well-formed; the snapshot is still renderable). If validateRenderable now keys off protocol 770, ensure the
  real recordings stamp protocol 770 (they do via McVersions on 1.21.5).
```bash
git add src/main/java/dev/zeffut/flashbackserver/snapshot/ src/test/java/dev/zeffut/flashbackserver/snapshot/
git -c commit.gpgsign=false commit -m "feat: build create_local_player from the Bukkit API (version-agnostic, manual GameProfile wire)"
```

## MV1 exit criteria
- Validator packet ids come from a protocol-keyed table read from the replay metadata, with graceful
  degradation for unknown protocols.
- `create_local_player` is built from the Bukkit API + manual GameProfile wire format — no NMS.
- All suites green; recordings/clips still validateRenderable on 1.21.5.

## Known follow-ups (MV2/MV3)
- MV2: dynamic snapshot (position/player-info/chunks) from CACHED CAPTURED bytes (version-agnostic).
- MV3: config+login NMS extracted into a per-version `SnapshotSeed`; multi-module build + runtime selection.
- The manual GameProfile wire format should be byte-verified against a real Flashback recording during the
  human spot-check (it feeds the synthetic create_local_player action which decode-verify skips).
