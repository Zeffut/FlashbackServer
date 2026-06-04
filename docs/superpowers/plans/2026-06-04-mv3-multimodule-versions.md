# MV3 — Multi-Module Version Adapters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use `- [ ]`.
> Part of multi-version (`docs/research/multiversion-architecture.md`, `mv2-spike.md`). MV1 done; cache (MV2) folded in.

**Goal:** Make Flashback Server build + run across multiple Minecraft versions (initial targets **1.21.5 and
1.21.8**, designed to add more) by isolating ALL version-locked NMS behind a `VersionAdapter` interface, with
per-version implementations selected at runtime. Keep main's v1.0.0 safe — all work on a branch, tests green
at each step.

**Strategy (incremental, lowest-risk-first):**
- **MV3a** — extract `VersionAdapter` interface + a single `v1_21_5` implementation, **in the current single
  module** (no build restructure). Behavior-preserving refactor; full suite stays green. This locks the seam.
- **MV3b** — split into Gradle subprojects (`:core` Paper-API-only, `:nms:v1_21_5` paperweight) and shade into
  one jar; runtime selection. Same behavior on 1.21.5.
- **MV3c** — add `:nms:v1_21_8` (paperweight 1.21.8); reconcile any NMS API diffs; bundle both.
- **MV3d** — cross-version IT (boot a 1.21.8 server, record, structural-validate) proving multi-version.

## The version-locked surface to move behind `VersionAdapter`
- `capture/ChannelAccess` → `channelOf(Player) : io.netty.channel.Channel`
- `snapshot/PacketSerializer` (encode game/config) + `snapshot/SnapshotBuilder` config/login/position/player-info/chunks construction → `buildSnapshotSeed(Player) : List<ReplayAction>` (NMS-built actions; `create_local_player` is added by core via Bukkit — version-agnostic, stays out)
- `snapshot/McVersions` → `protocolVersion()`, `dataVersion()`
- `verify/ReplayDecodeVerifier` decode → `decodeVerify(...)` (or keep version-tiered)
Version-AGNOSTIC (stay in core, NOT in the adapter): `format/*`, `clip/*`, `command/*`, `telemetry/*`,
`record/*` orchestration, `capture/PacketCapture` (Netty handler logic — uses `channelOf` from the adapter),
`snapshot/CreateLocalPlayerAction` (Bukkit API), `snapshot/SnapshotBuilder`'s assembly/ordering (calls the adapter for the NMS parts).

---

### MV3a: Extract `VersionAdapter` interface + v1_21_5 impl (single module, behavior-preserving)

**Files:** new `version/VersionAdapter.java` (interface), `version/VersionAdapters.java` (runtime selector),
`version/v1_21_5/V1_21_5Adapter.java` (impl wrapping the current NMS); refactor capture/snapshot/verify to
call the adapter; keep everything compiling in the one module.

- [ ] **Step 1: Define `version/VersionAdapter`** (interface, no NMS types in its signatures except
  `io.netty.channel.Channel` and our `format.ReplayAction`/`java.nio.file.Path` — Netty is fine, it's not NMS):
  ```java
  package dev.zeffut.flashbackserver.version;
  import dev.zeffut.flashbackserver.format.ReplayAction;
  import io.netty.channel.Channel;
  import org.bukkit.entity.Player;
  import java.nio.file.Path;
  import java.util.List;
  public interface VersionAdapter {
      Channel channelOf(Player player);
      int protocolVersion();
      int dataVersion();
      /** NMS-built initial-state actions: config(features/registries/tags) + login + position + player-info + chunks.
       *  (create_local_player is added by the caller via the Bukkit API.) Runs on the player's region thread. */
      List<ReplayAction> buildSnapshotSeed(Player player);
      /** Decode a chunk's game/config packets to count decoded/errors (for /replay verify). */
      ReplayDecodeResult decode(List<ReplayAction> snapshotActions, List<ReplayAction> streamActions);
      record ReplayDecodeResult(int decoded, int errors, List<String> problems) {}
  }
  ```
- [ ] **Step 2: Implement `version/v1_21_5/V1_21_5Adapter`** by MOVING the existing NMS code into it:
  - `channelOf` ← current `ChannelAccess.of`.
  - `protocolVersion`/`dataVersion` ← current `McVersions`.
  - `buildSnapshotSeed` ← current `SnapshotBuilder` config + login + position + player-info + chunks (everything
    EXCEPT the `create_local_player` action). Reuse `PacketSerializer`'s encode logic (move it here or keep it
    as a helper the adapter uses).
  - `decode` ← current `ReplayDecodeVerifier`'s decode loop (game codec + config codec).
- [ ] **Step 3: `version/VersionAdapters.current()`** — detect the server version and return the adapter.
  For now: `return new V1_21_5Adapter();` (single impl). Add a TODO + a small switch on
  `Bukkit.getMinecraftVersion()` returning the matching adapter (default to the only one, log a warning if the
  running version has no adapter — but still attempt, since 1.21.x are close).
- [ ] **Step 4: Rewire callers:**
  - `capture/PacketCapture` uses `adapter.channelOf(player)` instead of `ChannelAccess.of` (inject the adapter
    or call `VersionAdapters.current()`). Keep `ChannelAccess` only if still referenced; otherwise fold into the adapter.
  - `snapshot/SnapshotBuilder.dynamicActions` becomes: `adapter.buildSnapshotSeed(player)` for the NMS parts,
    then insert the `create_local_player` action (Bukkit) at the right position. (Re-confirm ordering: config →
    login → create_local_player → position → player-info → chunks. So buildSnapshotSeed returns config+login,
    then core inserts create_local_player, then seed's position+player-info+chunks — OR buildSnapshotSeed
    returns ALL NMS actions in order and the core splices create_local_player after login. Pick the cleaner
    split; document the contract precisely so ordering is preserved EXACTLY as today.)
  - `record/RecordingManager`, `clip/ClipManager` use `adapter.protocolVersion()/dataVersion()`.
  - `command/ReplayCommand` `/replay verify` uses `adapter.decode(...)` (the registryAccess supplier is no
    longer needed in the command — the adapter owns NMS). Update wiring.
  - Plugin: obtain the adapter once (`VersionAdapters.current()`), pass to the managers/capture as needed.
- [ ] **Step 5: Build + FULL suite.** `./gradlew build` green; `./gradlew test integrationTest` → ALL green
  (behavior identical on 1.21.5: recordings/clips still renderable, decode-verify errors=0). NMS confined to
  `version/v1_21_5/` only (grep `net.minecraft` → only that package). Commit.
```bash
git add -A
git -c commit.gpgsign=false commit -m "refactor: isolate all NMS behind a VersionAdapter (v1_21_5 impl); behavior-preserving"
```

---

### MV3b: Multi-module Gradle split (`:core` + `:nms:v1_21_5`) → one shaded jar

- [ ] **Step 1:** Restructure into Gradle subprojects:
  - `settings.gradle.kts`: include `:core`, `:nms:v1_21_5`. (Root applies shadow to bundle.)
  - `:core` — the plugin + all agnostic code + the `VersionAdapter` interface + `plugin.yml`/config.yml. Depends
    on the **Paper API** only (`paperweight.paperDevBundle` is for NMS — core should use `compileOnly` Paper API
    via `io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT` from the papermc repo, NOT the full dev bundle, so core
    has no NMS). Keep the test harness here (or a `:core` test source set) + run-paper.
  - `:nms:v1_21_5` — `V1_21_5Adapter` + the moved NMS; applies `paperweight-userdev` with the 1.21.5 dev bundle;
    depends on `:core` (for the interface + ReplayAction).
  - Root build: use `com.gradleup.shadow` (or `io.github.goooler.shadow`) to assemble ONE jar = core + nms
    module outputs (the reobf'd nms classes + core classes + plugin.yml). Ensure the bundled jar loads on Paper
    (paperweight mappings handled per nms module).
- [ ] **Step 2:** Runtime selection: `VersionAdapters.current()` loads the adapter class for the running
  version by FQN via reflection (`Class.forName("dev.zeffut.flashbackserver.version.v1_21_5.V1_21_5Adapter")`)
  so `:core` doesn't compile-depend on the nms modules. Map `Bukkit.getMinecraftVersion()` → adapter FQN.
- [ ] **Step 3:** Fix the build until `./gradlew build` produces one loadable jar and `./gradlew test integrationTest`
  is green (the harness deploys the shaded jar). This is the fiddly part — iterate on shadow config, paperweight
  per-module, and the test wiring. Commit when green.
```bash
git add -A
git -c commit.gpgsign=false commit -m "build: split into :core + :nms:v1_21_5, shade into one jar, reflective adapter selection"
```

---

### MV3c: Add `:nms:v1_21_8`

- [ ] **Step 1:** Add `:nms:v1_21_8` (paperweight dev bundle `1.21.8-R0.1-SNAPSHOT`), implementing
  `VersionAdapter` by copying `v1_21_5` and reconciling any NMS API differences (packet constructors,
  SynchronizeRegistriesTask, codecs may differ). Add the 1.21.8 protocol id row to `format/PacketIds` (look up
  the 1.21.8 protocol version + clientbound login/position/chunk/player-info ids — confirm empirically via a
  tiny probe if needed). Register its FQN in `VersionAdapters` keyed by version.
- [ ] **Step 2:** Build the bundled jar with BOTH adapters; `./gradlew build` green. Commit.
```bash
git add -A
git -c commit.gpgsign=false commit -m "feat: add :nms:v1_21_8 version adapter"
```

---

### MV3d: Cross-version integration test

- [ ] **Step 1:** Generalize the harness already supports `PaperTestServer.start(dir, port, project)`; add a
  param or overload to boot a specific **version** (e.g. download Paper 1.21.8 via `PaperDownloader.resolve(dir, "paper", "1.21.8")`). Add `CrossVersionRecordingIT` (`@Tag("integration")`) booting a **1.21.8** Paper
  server, recording a bot, and asserting `FlashbackValidator.validateRenderable` (or at least `validate` +
  `/replay verify errors=0`) — proving the right adapter is selected and recording works on a non-build version.
- [ ] **Step 2:** `./gradlew test integrationTest` → ALL green (1.21.5 + 1.21.8 ITs). Commit.
```bash
git add -A
git -c commit.gpgsign=false commit -m "test: cross-version IT — record on Paper 1.21.8 via its adapter"
```

## MV3 exit criteria
- All NMS behind `VersionAdapter`; `:core` has zero NMS; per-version `:nms:vX` modules; one shaded jar.
- Runtime selection by MC version; adapters for 1.21.5 + 1.21.8 (+ PacketIds rows).
- Cross-version IT proves recording works on a non-build version; full suite green; v1.0.0 behavior intact on 1.21.5.

## Known follow-ups
- Adding a future version = add `:nms:vX` + a PacketIds row (the documented per-version tax).
- The captured-state cache (MV2) remains a possible future optimization to shrink the seed.
- Bump the plugin to 1.1.0 once multi-version ships.
