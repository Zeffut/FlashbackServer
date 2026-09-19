# Flashback Server

> Server-side Flashback replay recording for Paper — zero dependencies, Folia-ready, with a clip system.

**Flashback Server** is a [Paper](https://papermc.io/) plugin that records gameplay into
**[Flashback](https://modrinth.com/mod/flashback)**-format replays entirely server-side — no client
mod required for the players being recorded. Drop the jar in `/plugins`, and you're recording.

## Features

- **Record any player server-side** — replays are saved as `.flashback` files readable by the
  Flashback client mod.
- **Clip system** — maintain a rolling buffer for a player and save the last *N* seconds on demand
  or automatically on death.
- **Auto-clip on death** — optionally save a clip whenever an armed player dies (config toggle).
- **Folia-ready** — works on the regionized Paper fork with no extra configuration.
- **Zero dependencies** — pure Netty packet capture; no ProtocolLib to break on every update.
- **Renderable snapshots** — each recording includes a synthesized initial-state snapshot
  (registries, chunks, player state) so the Flashback client can render it correctly.

## Why another replay plugin?

| | Flashback Server | Paper Flashback | server-replay |
|---|:---:|:---:|:---:|
| Platform | Paper/Folia 1.21.5–26.3 | Paper | Fabric |
| ProtocolLib required | ❌ **No** | ✅ Yes | — |
| Folia support | ✅ **Yes** | ❌ No | — |
| Clip system (save last N seconds) | ✅ **Yes** | ❌ No | ❌ No |
| Output format | `.flashback` | `.flashback` | `.mcpr` + Flashback |

## Installation

1. Requires **Paper** (or a Paper fork such as Folia) for **Minecraft 1.21.5–26.3**.
2. Drop `FlashbackServer-*.jar` into your server's `plugins/` folder.
3. Restart the server. No additional dependencies needed.
4. View recorded replays with the [Flashback](https://modrinth.com/mod/flashback) client mod.

## Commands

All commands require the permission `flashbackserver.replay` (default: **op**).

| Command | Description |
|---|---|
| `/replay start players <player>` | Start recording a player |
| `/replay stop players <player>` | Stop recording and save → `plugins/FlashbackServer/replays/<name>-<uuid>.flashback` |
| `/replay clip arm <player>` | Start a rolling clip buffer for a player |
| `/replay clip disarm <player>` | Stop and discard the rolling clip buffer |
| `/replay clip save <player>` | Save the current clip → `plugins/FlashbackServer/clips/<name>-clip-N.flashback` |
| `/replay verify <file>` | Decode-check a saved replay file (admin/diagnostic) |
| `/replay help` | Show the in-game help |

Tab-completion is supported for all subcommands and online player names.

## Configuration

`plugins/FlashbackServer/config.yml` is generated on first launch:

```yaml
clips:
  # Length of the rolling clip buffer, in seconds.
  # The actual saved clip will be approximately window-seconds..2×window-seconds
  # due to keyframe granularity.
  window-seconds: 30

  # Automatically save a clip when an armed player dies.
  # Requires clips to be armed for the player (/replay clip arm <player>).
  auto-clip-on-death: true
```

| Key | Default | Description |
|---|---|---|
| `clips.window-seconds` | `30` | Rolling clip length in seconds |
| `clips.auto-clip-on-death` | `true` | Save a clip automatically when an armed player dies |

## How it works (briefly)

Flashback Server captures raw Minecraft packets directly at the Netty layer. When a recording
starts, it injects a channel handler into the player's pipeline and captures all outbound packets.
Before appending live traffic, it synthesizes an initial-state snapshot (registry data, surrounding
chunks, and current player state) so the Flashback client has everything it needs to render the
replay from the beginning. Long recordings roll over to a new chunk automatically, and dimension
changes trigger a fresh snapshot.

## Compatibility

- Minecraft **1.21.5–26.3**
- [Paper](https://papermc.io/) and Paper forks (Purpur, Pufferfish, **Folia**)
- Replays are viewed with the [Flashback](https://modrinth.com/mod/flashback) client mod

> *Flashback Server is an independent project and is not affiliated with or endorsed by the
> Flashback mod or its author. It produces files compatible with the Flashback format for
> interoperability.*

## Status

Core recording, clip, and verify features are shipped. Visual rendering should be confirmed in
the Flashback client against your specific server version.

## Plugin API

Other plugins can start recordings and manage rolling clips programmatically.

**Consumer setup:**
- `plugin.yml`: `softdepend: [FlashbackServer]`
- Compile with `compileOnly` against the FlashbackServer jar (`dev.zeffut.flashbackserver.api`)
- **Do not shade** the API classes — a second copy of `FlashbackAPI` is never bound

**Classloading note:** if FlashbackServer is **not installed**, referencing `FlashbackAPI` throws
`NoClassDefFoundError`. Always check installation before touching API classes:

```java
import dev.zeffut.flashbackserver.api.ClipService;
import dev.zeffut.flashbackserver.api.FlashbackAPI;
import dev.zeffut.flashbackserver.api.RecordingService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

if (Bukkit.getPluginManager().getPlugin("FlashbackServer") == null) {
    return; // not installed — do not touch FlashbackAPI
}
if (!FlashbackAPI.isAvailable()) {
    return; // installed but disabled
}

// Re-fetch on each use; do not cache across plugin reloads.
RecordingService recording = FlashbackAPI.recording();
ClipService clips = FlashbackAPI.clips();

if (recording.start(player)) {
    // Default location
    CompletableFuture<Path> file = recording.stop(player);
    // Or custom path (absolute as-is; relative → plugins/FlashbackServer/…)
    // CompletableFuture<Path> file = recording.stop(player, Path.of("rounds/final.flashback"));
    file.thenAccept(path -> {
        if (path == null) return;
        ReplayCheckResult check = FlashbackAPI.verify(path); // API-side verify, any path
        if (!check.ok()) {
            // check.problems() / check.errorCount()
        }
    });
}

if (clips.arm(player)) {
    // Wait ≥1 tick before saveClip — first keyframe is built async; earlier calls return null.
    CompletableFuture<Path> clip = clips.saveClip(player);
    // Or: clips.saveClip(player, Path.of("highlights/kill-01.flashback"));
}
```

**Bukkit ServicesManager** (same classloading caveat; with `softdepend` the service is already
registered when your `onEnable` runs — no retry needed):

```java
RecordingService recording =
    Bukkit.getServicesManager().load(RecordingService.class);
ClipService clips =
    Bukkit.getServicesManager().load(ClipService.class);
if (recording == null || clips == null) {
    return; // FlashbackServer not enabled
}
```

**Threading:** service methods may be called from any thread. Returned futures complete on an
**async** thread — do not call Bukkit API in `whenComplete`/`thenAccept` without hopping back to
the player's region thread (`player.getScheduler().run(...)`).

Do not implement `RecordingService` / `ClipService` in production plugins (test doubles only) —
methods may be added in future releases.

| Service | Method | Notes |
|---|---|---|
| `RecordingService` | `start(Player)` | `false` if already recording |
| `RecordingService` | `stop(Player)` | default path; `CompletableFuture<Path>`; `null` if not recording; async |
| `RecordingService` | `stop(Player, Path)` | custom file path incl. name; `null` path → default; relative → data folder |
| `RecordingService` | `isRecording(Player)` | |
| `ClipService` | `arm(Player)` / `disarm(Player)` | `false` if already armed / not armed |
| `ClipService` | `isArmed(Player)` | |
| `ClipService` | `saveClip(Player)` | default path; `null` if not armed/not ready (wait ≥1 tick after arm); async |
| `ClipService` | `saveClip(Player, Path)` | custom file path incl. name; same readiness rules as above |
| `FlashbackAPI` | `verify(Path)` | format + packet-decode check on **any** `.flashback` path → `ReplayCheckResult` |

**`verify` semantics:** format/container checks always run. Packet decode is **skipped only** when the version adapter is unavailable (class not shaded / partial classpath) — then `decodeClean` is `null` and format alone decides `ok()`. Any other decoder failure (corrupt stream, unexpected exception, adapter instantiate error that is not a missing class) sets `decodeClean=false` and makes `ok()` false.

**Custom save paths (API):**
- `null` → default location under `replays/` or `clips/`
- absolute `Path` → written as-is (parent directories created)
- relative `Path` → resolved against `plugins/FlashbackServer/` (`plugin.getDataFolder()`)
- same path is overwritten; suffix not enforced (prefer `.flashback`)
- custom-path files are **API outputs**: call `FlashbackAPI.verify(path)` to validate them.
  `/replay verify` only scans the default `replays/` and `clips/` folders.

## Telemetry

Flashback Server collects **anonymous, opt-out** usage telemetry to help improve the plugin.
**No player data (names, UUIDs, IPs) is ever collected.**

What is sent:

| Event | Properties |
|---|---|
| `plugin_enabled` | Server platform (Paper/Folia), server version, Minecraft version, plugin version |
| `recording_saved` | File size in bytes |
| `recording_failed` | Exception class name only (e.g. `IOException`) |
| `clip_saved` | File size in bytes |
| `clip_failed` | Exception class name only |

All events are sent to [PostHog](https://posthog.com/) and include an anonymous server-scoped random UUID
(stored in `plugins/FlashbackServer/.telemetry-id`) — it is not linked to any player or operator identity.

**To disable**, set in `plugins/FlashbackServer/config.yml`:

```yaml
telemetry:
  enabled: false
```

## License

[MIT](LICENSE)
