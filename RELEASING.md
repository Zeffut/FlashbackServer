# Releasing Flashback Server

This is the pre-publication checklist for shipping Flashback Server to Modrinth. **Everything here is
prepared; the final Modrinth upload is a manual step you perform** (it needs your Modrinth account).

## 1. Build the release jar

```bash
./gradlew clean build reobfJar
```

The artifact to upload is the **reobfuscated** jar (Paper remaps it at load):

```
build/libs/FlashbackServer-<version>-reobf.jar
```

(The non-`-reobf` jar is the Mojang-mapped dev jar — do **not** upload that one.)

The version comes from `gradle.properties` (`version=…`) and is expanded into `plugin.yml` automatically.

## 2. Pre-publish gate — human visual spot-check (REQUIRED)

Automated tests prove every packet is **client-decodable** (`/replay verify` → `errors=0`), but the final
*visual* render can only be confirmed by a human. Before uploading:

1. Run a Paper **1.21.5** server with `FlashbackServer-<version>-reobf.jar` in `plugins/`.
2. Join, `/replay start players <you>`, move ~15s, `/replay stop players <you>`.
3. Open the produced `plugins/FlashbackServer/replays/*.flashback` in Minecraft 1.21.5 with the
   [Flashback](https://modrinth.com/mod/flashback) client mod.
4. Confirm it loads and renders you + the surrounding area. Repeat for a clip (`/replay clip arm`, play,
   `/replay clip save`).

If anything fails to render, capture the Flashback client log and fix before publishing (likely a snapshot
fidelity gap — see `docs/research/r3-initial-state.md`).

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
- Project type: **Plugin**; loaders: **Paper, Purpur, Folia**; MC version: **1.21.5**.
- Upload the `FlashbackServer-<version>-reobf.jar`, mark it as the primary file.
- Paste the summary/description/tags from `docs/modrinth.md`. Link the GitHub source. License: **MIT**.
- Note the independence disclaimer (not affiliated with the Flashback mod).

## 6. Post-publish

- Announce; watch the PostHog dashboard for adoption.
- Track issues on GitHub.
