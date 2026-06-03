# Flashback Server

> Server-side replay recording for Paper — **Flashback compatible**, **zero dependencies**, **Folia ready**.

**Flashback Server** is a [Paper](https://papermc.io/) plugin that records
gameplay into **[Flashback](https://modrinth.com/mod/flashback)**-format replays
**entirely server-side** — no client mod required for the players being
recorded. Drop the jar in `/plugins`, and you're recording.

## Why another replay plugin?

| | Flashback Server | Paper Flashback | server-replay |
|---|:---:|:---:|:---:|
| Platform | Paper 1.21.x | Paper | Fabric |
| ProtocolLib required | ❌ **No** | ✅ Yes | — |
| Folia support | ✅ **Yes** | ❌ No | — |
| Clip system (save last N seconds) | ✅ **Yes** | ❌ No | ❌ No |
| Output | Flashback | Flashback | .mcpr + Flashback |

- **Zero dependencies** — pure Netty packet capture, no ProtocolLib to break on
  every update.
- **Folia ready** — works on the multithreaded Paper fork.
- **Clip system** *(signature feature)* — automatically save the last *N*
  seconds when something happens (a kill, a death, a command, your own events).

## Status

🚧 **In development.** See the
[design spec](docs/superpowers/specs/2026-06-03-flashback-server-design.md) for
the roadmap.

## Compatibility

- Minecraft **1.21.x**
- [Paper](https://papermc.io/) and forks (Purpur, Pufferfish, **Folia**)
- Replays are viewed with the [Flashback](https://modrinth.com/mod/flashback)
  client mod.

> *Flashback Server is an independent project and is not affiliated with or
> endorsed by the Flashback mod or its author. It produces files compatible
> with the Flashback format for interoperability.*

## Privacy

Flashback Server sends **anonymous, opt-out** usage telemetry (no IPs, no
player names). Disable it with `telemetry: false` in the config.

## License

[MIT](LICENSE)
