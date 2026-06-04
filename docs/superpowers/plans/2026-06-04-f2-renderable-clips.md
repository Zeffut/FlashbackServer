# F2 — Renderable Clips Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make clips renderable like full recordings — a clip carries a real initial-state snapshot
representing the world/player state at the START of its window (not "now", which would double-apply the
buffered deltas). Proven: a saved clip decode-verifies (errors=0) AND passes `validateRenderable`.

**Architecture:** Split `SnapshotBuilder` into `configActions` (session-invariant: features/registries/tags)
and `dynamicActions` (login + create_local_player + position + player-info + chunks). `ClipManager` caches
the config once at arm. The `ClipBuffer` keeps TWO rolling segments — each `(dynamicKeyframe, frames-since)`
— rotating to a fresh keyframe every `window` ticks; this guarantees ≥ `window` ticks of content with a
keyframe that exactly matches the start of the retained frames. On save: snapshot = cachedConfig + the
older segment's keyframe; stream = older-segment frames ++ current-segment frames.

**Tech Stack:** Java 21, Paper/Folia 1.21.5, F1 `SnapshotBuilder`, P3 `PlatformScheduler`, P0 format,
harness, JUnit 5.

## Why not a save-time snapshot
A clip's buffered packets are deltas from window-start → now. A snapshot of "now" + replaying those deltas
would double-apply them (the client would see the end state, then watch the deltas re-happen). The snapshot
MUST be the state at the start of the retained frames — hence keyframes captured INTO the buffer over time.

---

### Task 1: Split `SnapshotBuilder` into config / dynamic / build

**Files:** `snapshot/SnapshotBuilder.java` (refactor).

- [ ] **Step 1:** Extract two public methods (keep `build` working):
  - `static List<ReplayAction> configActions(org.bukkit.entity.Player player)` → the `flashback:action/configuration_packet` actions (features → select-known-packs → registry-data → tags). Session-invariant.
  - `static List<ReplayAction> dynamicActions(org.bukkit.entity.Player player)` → login game_packet → `create_local_player` → position → player-info → view-distance chunks.
  - `static List<ReplayAction> build(Player player)` → `concat(configActions(player), dynamicActions(player))` (preserves the existing behavior used by `RecordingManager`).
  Both still must run on the player's region thread (Javadoc). NMS stays confined. Build green; existing
  recording ITs unaffected (build() identical output order).
- [ ] **Step 2:** `./gradlew build` green. Commit.
```bash
git add src/main/java/dev/zeffut/flashbackserver/snapshot/SnapshotBuilder.java
git -c commit.gpgsign=false commit -m "refactor: split SnapshotBuilder into configActions/dynamicActions/build"
```

---

### Task 2: Two-segment keyframe `ClipBuffer` (TDD)

Rework `ClipBuffer` so it carries rolling keyframes, not just frames.

**Files:** `clip/ClipBuffer.java` (rework), `clip/ClipBufferTest.java` (update/extend).

- [ ] **Step 1: Write the failing tests** (`ClipBufferTest`, tick-based via `ofTicks`). New model:
  - A clip needs a starting dynamic keyframe (`List<ReplayAction>`) + the frames after it.
  - `ClipBuffer.ofTicks(int windowTicks)`; `onPacket(byte[])`; `onTick()` (returns nothing); after `onTick`
    the buffer may need a new keyframe — `boolean needsKeyframe()` returns true when the current segment has
    reached `windowTicks` (or no keyframe set yet); `setKeyframe(List<ReplayAction> dynamic)` installs the
    keyframe for a new segment (rotating: the current segment becomes the "previous", a new current starts;
    keep at most 2 segments — dropping the oldest).
  - `List<ReplayAction> clipSnapshotActions()` → the dynamic keyframe of the OLDEST retained segment (or the
    only segment). `List<ReplayAction> clipStreamActions()` → all frames of the retained segments flattened
    (oldest first), each frame's packets as `game_packet` then a `next_tick`, plus trailing in-progress packets.
    `int clipTickCount()` → number of `next_tick`s in clipStreamActions.
  Tests to write:
  - `needsKeyframeWhenEmptyThenSeeded`: a fresh buffer `needsKeyframe()==true`; after `setKeyframe(k0)` it's false.
  - `rotatesAfterWindow`: ofTicks(2), setKeyframe(k0), onTick×2 → needsKeyframe()==true; setKeyframe(k1) rotates.
  - `clipSnapshotIsOldestKeyframe`: after seeding k0, onTick×2 (window), setKeyframe(k1), onTick×1, then
    `clipSnapshotActions()` equals k0 (oldest retained segment) and `clipStreamActions()` contains the frames
    from k0 onward (≥ window ticks). Use distinct sentinel actions for k0/k1 to assert which is returned.
  - `dropsThirdSegment`: seeding k0, rotate to k1, rotate to k2 → only k1,k2 retained; `clipSnapshotActions()`==k1.
  - `clipStreamTickCountWithinTwoWindows`: tick count is ≥ window and ≤ 2×window after steady-state rotations.
  (Design the rotation so there are at most 2 segments and `clipSnapshotActions` returns the oldest segment's
  keyframe — guaranteeing the snapshot matches the first retained frame's state.)
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement the two-segment `ClipBuffer`:
  - Internal: `record Segment(List<ReplayAction> keyframe, Deque<List<byte[]>> frames)`. A `Deque<Segment> segments` (≤2), `List<byte[]> current`, `int ticksInCurrentSegment`, `int windowTicks`.
  - `onTick()`: `segments.peekLast().frames().addLast(current)` (if a segment exists; if none, the frame is
    held pending until the first keyframe — simplest: ignore packets/ticks until first keyframe is set, since
    a clip with no keyframe can't render anyway); `current = new ArrayList<>()`; `ticksInCurrentSegment++`.
  - `needsKeyframe()`: `segments.isEmpty() || ticksInCurrentSegment >= windowTicks`.
  - `setKeyframe(dyn)`: push a new `Segment(dyn, new ArrayDeque<>())`; if `segments.size() > 2` removeFirst;
    `ticksInCurrentSegment = 0`.
  - `clipSnapshotActions()`: `segments.peekFirst().keyframe()` (oldest) or empty list if none.
  - `clipStreamActions()`: for each segment oldest→newest, for each frame: game_packet actions then next_tick;
    then trailing `current` packets as game_packets. (No next_tick for the in-progress frame.)
  - Thread-safe via one `ReentrantLock` (onPacket: Netty thread; onTick/setKeyframe: region thread; clip*: command thread).
  Keep the public seconds ctor `new ClipBuffer(int windowSeconds)` (×20) + `ofTicks`.
- [ ] **Step 4:** Run tests → PASS. `./gradlew build` green. Commit.
```bash
git add src/main/java/dev/zeffut/flashbackserver/clip/ClipBuffer.java src/test/java/dev/zeffut/flashbackserver/clip/ClipBufferTest.java
git -c commit.gpgsign=false commit -m "feat: two-segment keyframe ClipBuffer (renderable-clip foundation)"
```

---

### Task 3: `ClipManager` — cache config, seed + rotate keyframes, save with snapshot

**Files:** `clip/ClipManager.java`.

- [ ] **Step 1:** On `arm(player)`: build & cache `List<ReplayAction> config = SnapshotBuilder.configActions(player)`
  in the `Armed` record (build on the player's region thread via the entity scheduler one-shot, like
  RecordingManager seeds its snapshot). Seed the buffer's first keyframe:
  `SnapshotBuilder.dynamicActions(player)` → `buffer.setKeyframe(...)` (also on the region thread).
- [ ] **Step 2:** The per-tick callback (already `buffer::onTick`) becomes: `buffer.onTick(); if (buffer.needsKeyframe()) { schedule SnapshotBuilder.dynamicActions(player) on the region thread → buffer.setKeyframe(...) }`.
  Guard re-entrancy (don't schedule a new keyframe build while one is in flight — a simple `AtomicBoolean`
  per armed player, or check needsKeyframe again when applying). Keep it on the entity scheduler.
- [ ] **Step 3:** `saveClip(player)`: `snapshot = concat(cachedConfig, buffer.clipSnapshotActions())`;
  `stream = buffer.clipStreamActions()`; `ticks = buffer.clipTickCount()`; write via
  `ReplayFiles.write(out, name, McVersions.protocolVersion(), McVersions.dataVersion(), snapshot, stream, ticks)`
  on the async scheduler (as today). Logs `Saved clip: <path>` on success.
- [ ] **Step 4:** `./gradlew build` green, unit tests green. Commit.
```bash
git add src/main/java/dev/zeffut/flashbackserver/clip/ClipManager.java
git -c commit.gpgsign=false commit -m "feat: clips carry a real config+keyframe snapshot (renderable)"
```

---

### Task 4: Prove renderable clips end-to-end

**Files:** extend/add a clip IT.

- [ ] **Step 1:** Add `RenderableClipIT` (`@Tag("integration")`): bot joins, `replay clip arm <bot>`, wait ~5s
  (enough for ≥1 keyframe rotation + frames), `replay clip save <bot>`, find the clip under `clips/`, then:
  - `replay verify <clipfile>` → assert `errors=0` and `decoded` materially > 0 (config + keyframe packets present),
  - `FlashbackValidator.validateRenderable(clip).valid()` is true (now requires config + login + create_local_player + position + chunk — which the clip keyframe+config now provide).
- [ ] **Step 2:** Run `./gradlew integrationTest --tests '*RenderableClipIT' --tests '*ClipIT'` → PASS. If
  validateRenderable fails, the clip snapshot is missing an element — fix the ClipManager/SnapshotBuilder
  wiring (do not weaken). If decode errors>0, read the `Verify problem:` lines.
- [ ] **Step 3:** Full suites + commit. `./gradlew test integrationTest` → ALL green.
```bash
git add src/test/java/dev/zeffut/flashbackserver/
git -c commit.gpgsign=false commit -m "test: clips are renderable (config+keyframe snapshot, decode errors=0, validateRenderable)"
```

## F2 exit criteria
- Clips carry config + a dynamic keyframe matching the start of the retained window; no save-time double-apply.
- A saved clip decode-verifies with errors=0 and passes `validateRenderable`.
- ClipBuffer keyframe rotation is unit-tested; all suites green.

## Known follow-ups
- Keyframe build cost: dynamicActions (incl. chunks) every `window` per armed player — fine for armed
  players; if many are armed, consider throttling/shrinking the clip chunk radius. Note for F4/F6 config.
- Clip length is window..2×window by design (keyframe granularity) — document in README (F6).
- The final GL visual render remains the optional human spot-check.
