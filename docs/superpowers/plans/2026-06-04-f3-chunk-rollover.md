# F3 — Chunk Rollover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Recordings longer than the Flashback chunk length (6000 ticks = 5 min) — and dimension changes —
roll into multiple `cN.flashback` chunks instead of one oversized chunk, matching the Flashback format.
Chunk 0 carries the synthesized snapshot; later chunks continue from it.

**Architecture:** `FlashbackRecorder` partitions its action stream into chunks in memory: a list of completed
chunks + the in-progress chunk. It rolls when the in-progress chunk reaches `chunkLengthTicks` (default 6000,
injectable for tests) or on an explicit `rollChunk()` (dimension change). `stop()` writes `c0..cN` via
`ReplayFiles`/`FlashbackContainer`; `ChunkMeta` gains `forcePlaySnapshot`. `RecordingManager` calls
`rollChunk()` on `PlayerChangedWorldEvent`.

## File structure
- `format/ChunkMeta.java` — add `forcePlaySnapshot` (modify)
- `format/FlashbackMeta`/JSON — serialize `force_play_snapshot`/`forcePlaySnapshot` per chunk (verify key name)
- `record/ReplayFiles.java` — multi-chunk write (modify)
- `record/FlashbackRecorder.java` — in-memory chunk partitioning + rollChunk + injectable chunk length (modify)
- `record/RecordingManager.java` — wire PlayerChangedWorldEvent → rollChunk (modify)
- tests: recorder rollover unit test; existing ITs still green

---

### Task 1: ChunkMeta.forcePlaySnapshot + multi-chunk ReplayFiles + recorder rollover (TDD)

- [ ] **Step 1: `ChunkMeta`** — add `public boolean forcePlaySnapshot;` and a 2-arg constructor
  `ChunkMeta(int duration, boolean forcePlaySnapshot)` (keep the existing `ChunkMeta(int)` defaulting
  forcePlaySnapshot=false, and no-arg for Gson). Map it to the documented JSON key. Check the format doc
  (`docs/format/flashback-format.md`): the key is `forcePlaySnapshot` (camelCase in the FlashbackChunkMeta
  table). Add `@SerializedName("forcePlaySnapshot")`.

- [ ] **Step 2: Multi-chunk `ReplayFiles`** — add
  `static void write(Path output, String name, int protocol, int data, List<Chunk> chunks)` where
  `record Chunk(List<ReplayAction> snapshot, List<ReplayAction> stream, int tickCount, boolean forcePlaySnapshot)`.
  It builds `FlashbackMeta` with totalTicks = sum of chunk tickCounts, and one `chunks` entry `c{i}.flashback`
  → `new ChunkMeta(tickCount, forcePlaySnapshot)` per chunk, writing each chunk via `ChunkWriter.write(snapshot, stream)`
  and `container.writeChunk("c"+i+".flashback", bytes)`. Keep the existing single-chunk `write(...)` as a
  thin wrapper delegating to the list form with one chunk (forcePlaySnapshot=true). Put `Chunk` as a nested
  record in `ReplayFiles`.

- [ ] **Step 3: Write the failing recorder rollover test** `FlashbackRecorderRolloverTest`:
  ```java
  // recorder with a tiny chunk length (e.g. 3 ticks) via a test constructor/overload
  FlashbackRecorder r = FlashbackRecorder.withChunkLength(out, "P", 770, 4325, 3);
  r.setSnapshot(List.of(new ReplayAction("flashback:action/create_local_player", new byte[]{1})));
  for (int i=0;i<7;i++){ r.onPacket(new byte[]{(byte)i}); r.onTick(); }
  r.stop();
  var report = FlashbackValidator.validate(out);
  assertTrue(report.valid(), report.problems().toString());
  assertEquals(7, report.totalTicks());
  assertTrue(report.chunkCount() >= 3); // 7 ticks / 3 per chunk -> 3 chunks
  ```
  Also a test that `rollChunk()` forces an early boundary. (FlashbackValidator already sums next_tick across
  chunks for totalTicks — verify it iterates all chunks; it does per F1.)

- [ ] **Step 4: Implement recorder rollover.** `FlashbackRecorder` keeps `List<List<ReplayAction>> completedChunks`
  + `List<ReplayAction> currentChunk` + `int ticksInChunk` + `int totalTicks`. Add a package-private
  `static FlashbackRecorder withChunkLength(Path, name, proto, data, int chunkLengthTicks)` and a public ctor
  defaulting `chunkLengthTicks = 6000`. `onTick()`: add a next_tick to currentChunk, `ticksInChunk++`,
  `totalTicks++`; if `ticksInChunk >= chunkLengthTicks` → roll (move currentChunk to completedChunks, reset).
  `onPacket`: add game_packet to currentChunk. `rollChunk()`: if currentChunk non-empty, roll immediately
  (public, called on dimension change). `stop()`: roll the final currentChunk; build `List<ReplayFiles.Chunk>`:
  chunk 0 → snapshot = the set snapshot, forcePlaySnapshot=true; chunks 1..N → empty snapshot, forcePlaySnapshot=false;
  each chunk's stream = its actions, tickCount = its next_tick count. Write via `ReplayFiles.write(..., chunks)`.
  Keep the lock + `stopped` guard. Run test → PASS.

- [ ] **Step 5: Build + commit.** `./gradlew build` green (update `FlashbackRecorderTest` if the single-chunk
  path changed — keep its behavior: a short recording = 1 chunk with snapshot).
```bash
git add src/main/java/dev/zeffut/flashbackserver/format/ src/main/java/dev/zeffut/flashbackserver/record/ src/test/java/dev/zeffut/flashbackserver/record/
git -c commit.gpgsign=false commit -m "feat: chunk rollover at 6000 ticks + per-chunk forcePlaySnapshot"
```

---

### Task 2: Wire dimension change + verify

- [ ] **Step 1: `RecordingManager`** — implement `PlayerChangedWorldEvent` handler: if recording the player,
  call `recorder.rollChunk()` (find the recorder via the Active entry). (RecordingManager already implements
  Listener.) Build green.

- [ ] **Step 2: Verify** — existing `RecordingIT`/`RenderableRecordingIT`/`SnapshotFidelityIT`/`DecodeVerifyIT`
  still pass (short recordings = 1 chunk, unchanged). Run `./gradlew test integrationTest` → ALL green.
  (No new IT needed — the rollover is unit-tested with a tiny chunk length; a 5-min IT is impractical.)

- [ ] **Step 3: Commit.**
```bash
git add src/main/java/dev/zeffut/flashbackserver/record/RecordingManager.java
git -c commit.gpgsign=false commit -m "feat: roll a new chunk on dimension change"
```

## F3 exit criteria
- Recordings roll into multiple chunks at the chunk-length threshold and on dimension change; chunk 0 carries
  the snapshot (forcePlaySnapshot=true), later chunks empty (false); metadata totalTicks/durations consistent.
- Rollover unit-tested with an injectable chunk length; all suites green.

## Known follow-ups
- Later chunks have empty snapshots (seek into them replays from chunk 0) — acceptable; per-chunk snapshots
  (with config de-dup) are a fidelity optimization.
- In-memory buffering of all chunks until stop — fine for v1; streaming-to-disk is a future memory optimization.
