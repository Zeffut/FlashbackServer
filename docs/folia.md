# Folia Spike — Go/No-Go Assessment

## Spike method

`FoliaSpikeIT` (now deleted) booted Folia 1.21.5 via the generalised
`PaperTestServer.start(dir, 25630, "folia")`, connected `BotClient` ("SpikeBot"),
then issued `replay start players SpikeBot` and `replay stop players SpikeBot` via stdin.
Run: `./gradlew integrationTest --tests '*FoliaSpikeIT'`

---

## 1. Does Folia 1.21.5 boot in this environment?

**Yes.** Folia `1.21.5-12-ver/1.21.5@dfa3ca4` boots cleanly on macOS ARM64 (Apple Silicon).

```
[folia] [08:32:34 INFO]: Done (7.376s)! For help, type "help"
```

Boot time from process launch to `Done (`: **~7.4 seconds** (flat world, no existing data).

---

## 2. What failure does the current plugin hit on Folia?

Folia refused to load the plugin at all, before any game code runs:

```
[folia] [08:32:30 ERROR]: [DirectoryProviderSource] Error loading plugin:
  Could not load plugin 'FlashbackServer v0.1.0-SNAPSHOT' as it is not marked as supporting Folia!
[folia] java.lang.RuntimeException: Could not load plugin 'FlashbackServer v0.1.0-SNAPSHOT'
         as it is not marked as supporting Folia!
    at io.papermc.paper.plugin.provider.type.spigot.SpigotPluginProviderFactory.build(SpigotPluginProviderFactory.java:40)
    at io.papermc.paper.plugin.provider.type.spigot.SpigotPluginProviderFactory.build(SpigotPluginProviderFactory.java:28)
    ...
[folia] [08:32:30 INFO]: [PluginInitializerManager] Initialized 0 plugins
```

Because the plugin never loaded, the `replay` command was never registered, so issuing
`replay start players SpikeBot` produced:

```
Unknown or incomplete command, see below for error: replay start players SpikeBot <--[HERE]
```

**The `UnsupportedOperationException` from `TickClock.start()` was never reached.**
Folia's load-time gate (`folia-supported: true` in `plugin.yml`) fires first.

### Root cause

`plugin.yml` lacks the `folia-supported: true` key. Folia requires it as an explicit opt-in.
Adding it will allow the plugin to load — and *then* `TickClock.start()` will throw
`UnsupportedOperationException` because `plugin.getServer().getScheduler().runTaskTimer(…)` is
the global BukkitScheduler, which is not supported on Folia's regionised threading model.

### Fix sequence (Tasks 2–3)

1. Add `folia-supported: true` to `plugin.yml` so Folia actually loads the plugin.
2. Replace `TickClock`'s global `BukkitScheduler` call with a Folia-compatible scheduler
   (e.g. `plugin.getServer().getRegionScheduler()` or `entity.getScheduler()`), while
   keeping the Paper path for non-Folia servers.

---

## 3. Verdict

**GO.**

Folia 1.21.5 boots cleanly in this environment (~7.4 s). The two blockers are both
straightforward code changes:

| Blocker | Fix |
|---|---|
| Plugin rejected at load time | Add `folia-supported: true` to `plugin.yml` |
| `TickClock` uses global `BukkitScheduler` | Switch to `RegionScheduler` / entity scheduler under Folia |

No environmental issues were encountered. Proceeding to adopt the regionised scheduler API
(Task 3) is safe.
