# F5 — PostHog Telemetry (opt-out, no PII) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Anonymous, opt-out, no-PII usage telemetry to PostHog so the maintainer can see adoption/usage.
Never blocks the server, never sends player data, fully disableable in config.

## Privacy contract (NON-NEGOTIABLE)
- **No PII:** never send player names, UUIDs, IPs, world names, or chat. `distinct_id` is an anonymous,
  per-server random UUID persisted in the data folder (identifies the server install, not any person).
- **Opt-out:** `telemetry.enabled: true` by default, but a clear first-run log line says it's on + how to
  disable. Setting it false (or a blank project key) makes Telemetry a complete no-op.
- **Non-blocking:** sends are async (`HttpClient.sendAsync`), failures are swallowed — telemetry can never
  affect gameplay or throw into server threads.
- README documents exactly what's collected.

## What's collected (events)
- `plugin_enabled` — props: `platform` (Paper/Folia), `server_version`, `plugin_version`, `mc_version`.
- `recording_saved` — props: `tick_count`, `file_bytes`.
- `clip_saved` — props: `tick_count`, `file_bytes`.
- `recording_failed` / `clip_failed` — props: `reason_class` (exception class name, no message/PII).
All events also carry `$lib=flashback-server`, `plugin_version`. NOTHING player-identifying.

## File structure
- `telemetry/Telemetry.java` — new: builds + async-POSTs PostHog capture events; no-op when disabled
- `telemetry/TelemetryTest.java` — payload shape + opt-out no-op + no-PII (unit, no network)
- `src/main/resources/config.yml` — telemetry section (modify)
- `FlashbackServerPlugin.java` — read config, create Telemetry, fire plugin_enabled, pass to managers (modify)
- `record/RecordingManager.java`, `clip/ClipManager.java` — fire recording_saved/clip_saved on success (modify)
- `README.md` — Telemetry section (modify)

---

### Task 1: `Telemetry` class (TDD payload + opt-out)

- [ ] **Step 1: Write `TelemetryTest`** (no network — test the payload builder + gating):
  Make `Telemetry` expose a package-private pure method `String buildPayload(String event, Map<String,Object> props)`
  returning the JSON body, and `boolean isEnabled()`. Tests:
  - `buildPayloadHasApiKeyEventDistinctId`: payload contains the api key, `"event":"plugin_enabled"`,
    `"distinct_id"`, and the props nested under `properties`, plus `$lib`.
  - `disabledTelemetryIsNoOp`: a `Telemetry` constructed with `enabled=false` → `isEnabled()` false and
    `capture(...)` does nothing/sends nothing (use a constructor flag; assert no throw, isEnabled false).
  - `blankKeyDisables`: enabled=true but blank/placeholder key → isEnabled() false.
  - `payloadHasNoPII`: build a payload and assert it does NOT contain a sample player name passed only as…
    (don't pass PII at all — instead assert the builder only includes the given props; document that callers
    must not pass PII). Keep this test simple: assert the payload contains only the keys we put in.

- [ ] **Step 2: Implement `Telemetry`**:
  ```java
  package dev.zeffut.flashbackserver.telemetry;
  // fields: boolean enabled; String host; String projectKey; String distinctId; String pluginVersion; Logger; HttpClient
  // enabled := enabledConfig && projectKey is non-blank && !projectKey.equals("phc_REPLACE_WITH_YOUR_KEY")
  // capture(String event, Map<String,Object> props): if (!enabled) return; build payload; HttpClient.newHttpClient()
  //   .sendAsync(POST host + "/i/v0/e/", BodyPublishers.ofString(json), discarding).exceptionally(t -> null);
  // buildPayload: minimal JSON via a tiny manual builder or Gson (Gson is compileOnly+testImpl — fine to use):
  //   {"api_key":..., "event":..., "distinct_id":..., "properties": {props..., "$lib":"flashback-server", "plugin_version":...}}
  ```
  Use Gson for safe JSON (already a dep). Endpoint: `host + "/i/v0/e/"` (PostHog capture). Build the
  HttpClient once. All sends async + `.exceptionally`. Never throw from `capture`.
  Add a `static String loadOrCreateDistinctId(Path dataFolder)` helper: read `dataFolder/.telemetry-id`
  (a UUID), create+persist if absent. (Anonymous server id.)

- [ ] **Step 3: Build + test.** `./gradlew build` green, TelemetryTest passes.
```bash
git add src/main/java/dev/zeffut/flashbackserver/telemetry/ src/test/java/dev/zeffut/flashbackserver/telemetry/
git -c commit.gpgsign=false commit -m "feat: anonymous opt-out PostHog telemetry (no PII, non-blocking)"
```

---

### Task 2: Config + wiring + README

- [ ] **Step 1: `config.yml`** — add (with comments):
  ```yaml
  telemetry:
    # Anonymous, opt-out usage telemetry (no player data, no IPs). Set to false to disable.
    # What's sent: server platform/version, plugin version, recording/clip counts & sizes, error class names.
    enabled: true
    posthog:
      # PostHog ingestion host. Use https://eu.i.posthog.com if your PostHog project is on EU Cloud.
      host: "https://us.i.posthog.com"
      # PostHog project API key (public/write-only). Replace with your own project's key.
      project-key: "phc_zdMj4p5wo8EvfVApjb2EbfUHJ76zgYGM5wAGz5YJC359"
  ```

- [ ] **Step 2: Wire in `FlashbackServerPlugin.onEnable`** — read the config; create
  `Telemetry telemetry = new Telemetry(enabled, host, key, Telemetry.loadOrCreateDistinctId(getDataFolder().toPath()), getDescription().getVersion(), getLogger())`.
  If `telemetry.isEnabled()`, log ONE line: `"Anonymous telemetry is enabled (no player data). Disable it with telemetry.enabled: false in config.yml."`.
  Fire `telemetry.capture("plugin_enabled", Map.of("platform", foliaOrPaper(), "server_version", getServer().getVersion(), "mc_version", getServer().getMinecraftVersion(), "plugin_version", getDescription().getVersion()))`.
  (`foliaOrPaper()` = try `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` → "Folia" else "Paper".)
  Pass `telemetry` into `RecordingManager` and `ClipManager` constructors.

- [ ] **Step 3: Fire events on success** — in `RecordingManager.stop`'s async success branch, after writing,
  `telemetry.capture("recording_saved", Map.of("tick_count", <ticks>, "file_bytes", Files.size(out)))`; on
  failure `telemetry.capture("recording_failed", Map.of("reason_class", e.getClass().getSimpleName()))`. Same
  for `ClipManager.saveClip` → `clip_saved` / `clip_failed`. (tick count: RecordingManager passes the recorder's
  total ticks — expose a getter on FlashbackRecorder if needed, or compute from the chunks; keep it simple — if a
  tick count isn't readily available, send only file_bytes.) NO player identifiers in any property.

- [ ] **Step 4: README Telemetry section** — replace "None currently" with an honest description: what's
  collected (the event list above), that it's anonymous/no-PII/opt-out, and how to disable
  (`telemetry.enabled: false`). 

- [ ] **Step 5: Build + verify.** `./gradlew build` green; `./gradlew test integrationTest` → ALL green.
  (Telemetry sends are async fire-and-forget; in ITs they'll attempt a network POST that may fail silently —
  that must NOT break any IT. If an IT becomes flaky due to telemetry, ensure the test server's config has
  telemetry disabled OR that failures are fully swallowed. Prefer: the no-op/swallow guarantees it's safe;
  if needed, the harness can set telemetry.enabled=false in the generated config.)
```bash
git add -A
git -c commit.gpgsign=false commit -m "feat: wire telemetry (plugin_enabled, recording/clip saved+failed) + config + README"
```

## F5 exit criteria
- Telemetry is anonymous (server UUID, no PII), opt-out (config + first-run log), non-blocking, no-op when disabled.
- Events fire for enable + recording/clip saved/failed.
- README documents it; all suites green; telemetry never breaks an IT.

## Known follow-ups (for the human)
- The shipped project key belongs to the existing "Default project" (shared with another app) — create a
  dedicated PostHog project for Flashback Server and swap the key.
- Confirm the PostHog region/host (US vs EU).
- A PostHog dashboard for these events (F6 / post-publish).
