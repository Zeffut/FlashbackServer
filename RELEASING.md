# Releasing Flashback Server

This is the pre-publication checklist for shipping Flashback Server to Modrinth. The final upload is performed with the authenticated Modrinth API after the checks below pass.

## 1. Build and verify the release jar

```bash
./gradlew clean :nms:v26_2:build :core:test paper26_2Smoke --no-daemon
```

The artifact to upload is the bundled root jar:

```
build/libs/FlashbackServer-<version>.jar
```

`shadowJar` assembles the deployable plugin: it bundles `:core`, the reobfuscated 1.21.x adapters, and the
Mojang-mapped 26.x adapters. The focused `paper26_2Smoke` boots a temporary Paper 26.2 server with that exact
jar, checks plugin enablement, and performs a controlled shutdown. Inspect `plugin.yml` and record the SHA-512
of the final archive before upload.

The version comes from `gradle.properties` (`version=…`) and is expanded into `plugin.yml` automatically.

## 2. Pre-publish gate — reproducible automated smoke

The release gate is the build/test/smoke command above. It runs the core tests and an actual headless Paper 26.2
server lifecycle; no graphical Minecraft client launch or human visual review is required for publication.

A manual replay-rendering check remains useful as additional exploratory QA. If it exposes a decoding or snapshot
fidelity defect, capture the Flashback client log and fix it before the next release (see
`docs/research/r3-initial-state.md`).

## 3. Telemetry — before you publish

The shipped `config.yml` points telemetry at a PostHog project key. **Action items:**

- **Use a dedicated PostHog project** for Flashback Server. The current key
  (`phc_zdMj4p5wo8EvfVApjb2EbfUHJ76zgYGM5wAGz5YJC359`) belongs to your existing "Default project" (shared
  with another app). Create a new project, copy its API key into `config.yml` → `telemetry.posthog.project-key`,
  and rebuild.
- **Confirm the region/host.** Default is `https://us.i.posthog.com`. If your PostHog project is on EU Cloud,
  set `telemetry.posthog.host: https://eu.i.posthog.com`.
- (Optional) Build a PostHog dashboard for the events: `plugin_enabled`, `recording_saved`, `clip_saved`,
  `recording_failed`, `clip_failed`.

## 4. Tag the release (git/GitHub)

```bash
git tag -a v<version> -m "Flashback Server v<version>"
git push origin v<version>
```

(Optionally create a GitHub Release from the tag and attach the `-reobf.jar`.)

## 5. Modrinth upload (manual — your account)

Create the project on Modrinth using the metadata in [`docs/modrinth.md`](docs/modrinth.md):
- Project type: **Plugin**; loaders: **Paper, Purpur, Folia**; MC versions: **1.21.5–26.3**.
- Upload the `FlashbackServer-<version>-reobf.jar`, mark it as the primary file.
- Paste the summary/description/tags from `docs/modrinth.md`. Link the GitHub source. License: **MIT**.
- Note the independence disclaimer (not affiliated with the Flashback mod).

## 6. Post-publish

- Announce; watch the PostHog dashboard for adoption.
- Track issues on GitHub.
