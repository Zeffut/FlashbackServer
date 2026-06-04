# F4 — UX & Cleanliness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Make the plugin "ultra clean" and pleasant to use: permissions, tab-completion, `/replay help`,
sender feedback on async operations; remove vestigial/dead code; accurate README + commented config.

## File structure
- `command/ReplayCommand.java` — permissions, help, sender feedback (modify)
- `command/ReplayTabCompleter.java` — tab completion (new)
- `src/main/resources/plugin.yml` — permission node + command permission (modify)
- `FlashbackServerPlugin.java` — register tab completer; drop CaptureListener registration (modify)
- Remove `capture/CaptureListener.java` + `test/.../capture/PacketCaptureIT.java` (vestigial P2a proof, superseded)
- `record/RecordingManager.java` — harden silent-empty-snapshot warning (modify)
- `verify/ReplayDecodeVerifier.java` — remove unused `ok()` if unused (modify)
- `README.md`, `src/main/resources/config.yml` — accuracy/comments

---

### Task 1: Command UX — permissions, tab-completion, help, feedback

- [ ] **Step 1: Permission node** in `plugin.yml`:
```yaml
permissions:
  flashbackserver.replay:
    description: Use the /replay command (record, clip, verify).
    default: op
```
And in `ReplayCommand.onCommand`, first check `if (!sender.hasPermission("flashbackserver.replay")) { sender.sendMessage("You don't have permission."); return true; }`.

- [ ] **Step 2: `/replay help`** — add a `help` branch (and make no-args / unknown print it) listing:
  `/replay start|stop players <player>`, `/replay clip arm|disarm|save <player>`, `/replay verify <file>`,
  `/replay help`. Keep it concise.

- [ ] **Step 3: Sender feedback on async ops.** `RecordingManager.stop` and `ClipManager.saveClip` return
  `CompletableFuture<Path>`. In `ReplayCommand`, on stop/clip-save, attach
  `future.whenComplete((path, err) -> ...)` that messages the SENDER on the main thread (hop via
  `sender.getServer().getGlobalRegionScheduler().run(plugin, t -> sender.sendMessage(...))` — Folia-safe and
  works on Paper; the command needs the `Plugin` — pass it into ReplayCommand's constructor if not already).
  Message: success → "Saved replay/clip: <filename>"; failure → "Replay/clip failed: <msg>". Keep the
  immediate "…writing…" ack too. (For a console sender this is fine; the scheduler hop makes player senders safe.)

- [ ] **Step 4: `ReplayTabCompleter`** implements `org.bukkit.command.TabCompleter`:
  - arg 0 → {start, stop, clip, verify, help} (filtered by prefix).
  - if arg0 ∈ {start,stop} → arg1 {players}; arg2 → online player names.
  - if arg0 == clip → arg1 {arm, disarm, save}; arg2 → online player names.
  - if arg0 == verify → arg1 → list `.flashback` filenames under replays/ + clips/ dirs (best-effort; empty on error).
  Register in the plugin: `getCommand("replay").setTabCompleter(new ReplayTabCompleter(replaysDir, clipsDir))`.

- [ ] **Step 5: Build + commit.** `./gradlew build` green.
```bash
git add src/main/java/dev/zeffut/flashbackserver/command/ src/main/resources/plugin.yml src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java
git -c commit.gpgsign=false commit -m "feat: /replay permissions, tab-completion, help, async sender feedback"
```

---

### Task 2: Remove vestigial/dead code + harden

- [ ] **Step 1: Remove the vestigial `CaptureListener`** (the P2a packet counter). Delete
  `capture/CaptureListener.java`, remove its registration + field in `FlashbackServerPlugin`, and delete
  `test/.../capture/PacketCaptureIT.java` (it asserted the `[capture]` counter log; capture is now proven by
  `RecordingIT`/`DecodeVerifyIT`/`SnapshotFidelityIT`). Confirm nothing else references CaptureListener.
- [ ] **Step 2: Remove dead code** — if `verify/ReplayDecodeVerifier.Result.ok()` is unused anywhere, remove
  it (grep first; keep if a test/command uses it). Remove any other obviously-unused helpers you find in the
  touched files (be conservative — only clearly-dead code).
- [ ] **Step 3: Harden silent-empty-snapshot.** In `RecordingManager.start`'s snapshot-build region task,
  if `SnapshotBuilder.build` throws, the current code logs a warning and the recording proceeds with an empty
  (non-renderable) snapshot. Upgrade the log to `getLogger().severe("Snapshot build failed for <name>; this recording will NOT be renderable: <msg>")` so it's unmistakable. (Do not abort the recording — a non-renderable file is better than none, but the operator must be told loudly.)
- [ ] **Step 4: Build + verify.** `./gradlew build` green; `./gradlew test integrationTest` → ALL green
  (PacketCaptureIT removed; the rest still pass). 
```bash
git add -A
git -c commit.gpgsign=false commit -m "chore: remove vestigial CaptureListener + dead code; loud warning on snapshot build failure"
```

---

### Task 3: README + config accuracy

- [ ] **Step 1: Rewrite `README.md`** to reflect the real plugin:
  - One-line: "Server-side Flashback replay recording for Paper — zero dependencies, Folia-ready, with a clip system."
  - Features: record players to `.flashback`; clips (save last N seconds, auto-clip on death); Folia support;
    zero deps (no ProtocolLib); renderable snapshots (config + chunks). 
  - Commands table: `/replay start|stop players <player>`, `/replay clip arm|disarm|save <player>`,
    `/replay verify <file>`, `/replay help`. Permission `flashbackserver.replay`.
  - Config: document `clips.window-seconds`, `clips.auto-clip-on-death`.
  - Install: drop the jar in `plugins/`, Paper/Folia 1.21.5, view replays with the Flashback client mod.
  - Compatibility + the independence disclaimer (not affiliated with Flashback). Keep the privacy note
    (telemetry) as "coming in a future version" OR remove until F5 lands — keep it accurate to current state.
  - Update the comparison table if present; remove "in development" wording for shipped features (note clips
    are window..2×window long by design).
- [ ] **Step 2: Comment `config.yml`** clearly (already has clips keys — ensure each has a `#` explanation).
- [ ] **Step 3: Commit.**
```bash
git add README.md src/main/resources/config.yml
git -c commit.gpgsign=false commit -m "docs: accurate README (commands, config, features, install) + commented config"
```

## F4 exit criteria
- `/replay` has a permission node, tab-completion, help, and tells the sender when async saves finish.
- Vestigial CaptureListener + its IT removed; no dead code in touched files; snapshot-build failure is loud.
- README accurately documents commands/config/features/install; config.yml is commented.
- All suites green.
