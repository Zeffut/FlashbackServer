# Modrinth project metadata

Copy-paste material for the Modrinth project page. (The actual upload is manual — see `RELEASING.md`.)

## Basics
- **Name:** Flashback Server
- **Slug:** flashback-server
- **Project type:** Plugin
- **Loaders:** Paper, Purpur, Folia
- **Minecraft versions:** 1.21.5–1.21.11
- **License:** MIT
- **Source:** https://github.com/Zeffut/FlashbackServer
- **Client/server:** Server-side (required on server; not required on client). Replays are viewed with the
  [Flashback](https://modrinth.com/mod/flashback) client mod.

## Summary (short description, ≤256 chars)
> Server-side Flashback replay recording for Paper — zero dependencies, Folia-ready, with a clip system. Record any player and save the last N seconds as a clip, no client mod required for the recorded players.

## Categories / tags
`utility`, `management`, `social` (pick those Modrinth offers for plugins). Suggested search tags:
`replay`, `flashback`, `recording`, `clips`, `folia`, `paper`.

## Description (long, Markdown)

```markdown
# Flashback Server

Record your Paper/Folia server's gameplay into **[Flashback](https://modrinth.com/mod/flashback)**-format
replays — **entirely server-side**. The players being recorded need no mods; you view the results with the
Flashback client mod.

## Features
- **Record any player server-side** → `.flashback` files readable by the Flashback client.
- **Clip system** — keep a rolling buffer per player and save the last *N* seconds on demand, or
  automatically when a player dies.
- **Folia-ready** — works on the regionized Paper fork, no extra config.
- **Zero dependencies** — pure Netty packet capture; no ProtocolLib to break on every update.
- **Renderable snapshots** — recordings include a synthesized initial state (registries, chunks, player) so
  the Flashback client renders them from the start.
- **Multi-chunk** recordings for long sessions + dimension changes.

## Commands (`flashbackserver.replay`, default op)
| Command | Description |
|---|---|
| `/replay start players <player>` | Start recording a player |
| `/replay stop players <player>` | Stop & save a recording |
| `/replay clip arm\|disarm\|save <player>` | Rolling clip buffer controls |
| `/replay verify <file>` | Decode-check a saved replay (diagnostic) |
| `/replay help` | Help |

## Configuration
- `clips.window-seconds` (default 30) — rolling clip length.
- `clips.auto-clip-on-death` (default true) — auto-save a clip when an armed player dies.
- `telemetry.enabled` (default true) — anonymous, no-PII usage telemetry; set false to disable.

## Install
1. Paper or a Paper fork (Purpur/Folia) for **Minecraft 1.21.5–1.21.11**.
2. Drop the jar in `plugins/`, restart. No dependencies.
3. View replays with the [Flashback](https://modrinth.com/mod/flashback) client mod.

## Privacy
Anonymous, opt-out telemetry (server platform/version, recording/clip counts & sizes, error class names).
**No player names, UUIDs, or IPs are ever collected.** Disable with `telemetry.enabled: false`.

---
*Flashback Server is an independent project, not affiliated with or endorsed by the Flashback mod or its
author. It produces files compatible with the Flashback format for interoperability.*
```

## Gallery / icon (optional, for later)
- A 64×64+ icon and a short GIF of a recorded replay playing in Flashback would boost the page.
