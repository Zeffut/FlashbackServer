# F1 — Task 1 GO/NO-GO spike: synthesize CONFIGURATION-phase packets from a live `ServerPlayer`

> Goal: prove we can build the CONFIGURATION-phase snapshot packets (enabled features, registry data,
> tags) from a LIVE `ServerPlayer` on Paper 1.21.5 and serialize them to the exact `varint id + payload`
> bytes our capture pipeline uses (uncompressed, unframed). Plus confirm the live protocol/data version
> accessors. Companion to `r3-spike.md` (which proved the PLAY-phase packets).
>
> Date: 2026-06-04. Method: temporary `F1ConfigProbe` + `F1SpikeIT` booting real Paper 1.21.5 via the
> harness, an MCProtocolLib bot joins, probe fires ~2s (40 ticks) after join on the player's entity
> scheduler, `[cfg]`/`[ver]` lines captured from server stdout. Probe + temp test removed after the
> spike (this doc is the artifact).

## VERDICT: **GO**

All three CONFIGURATION-phase packet groups were built from the live `ServerPlayer` / `MinecraftServer`
and serialized to `id+payload` bytes server-side, with NO real client involved, plus both live versions.
Observed (`[cfg]`/`[ver]` log, real run):

```
[cfg] === probe start for Bot ===
[ver] protocol=770 data=4325
[cfg] ClientboundUpdateEnabledFeaturesPacket bytes=20 id=12
[cfg] SynchronizeRegistriesTask.start emitted 1 packet(s) (expected: select-known-packs)
[cfg] ClientboundSelectKnownPacks bytes=24 id=14
[cfg] SynchronizeRegistriesTask.handleResponse(empty) emitted 21 registry-data packet(s)
[cfg] ClientboundRegistryDataPacket bytes=24601 id=7     # (×21, sizes 94 .. 29952)
...
[cfg] ClientboundUpdateTagsPacket bytes=27199 id=13
[cfg] registry-data total bytes=120057
[cfg] === probe end ===
```

`bytes` is the full encoded packet INCLUDING the leading varint id (the codec writes the id first, then
payload). `id` is that leading varint decoded — these are the 1.21.5 clientbound CONFIGURATION packet
ids. This is exactly the byte shape Flashback records as `flashback:action/configuration_packet`.

---

## The CONFIGURATION codec — encode call (no registry decorator)

`net.minecraft.network.protocol.configuration.ConfigurationProtocols.CLIENTBOUND` is **already a bound**
`ProtocolInfo<ClientConfigurationPacketListener>` over plain `FriendlyByteBuf` — it does NOT need a
`RegistryFriendlyByteBuf.decorator(...)` (unlike the PLAY template `GameProtocols.CLIENTBOUND_TEMPLATE`,
which is `ProtocolInfo.Unbound` and must be `bind(...)`-ed). Just call `.codec()`:

```java
StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> codec =
        ConfigurationProtocols.CLIENTBOUND.codec();   // already bound; no decorator

ByteBuf buf = Unpooled.buffer();
codec.encode(buf, packet);                 // buf = [varint id][payload]
byte[] idAndPayload = ByteBufUtil.getBytes(buf);
buf.release();
```

(`javap` confirms `ConfigurationProtocols.CLIENTBOUND` is a `ProtocolInfo<ClientConfigurationPacketListener>`
and `CLIENTBOUND_TEMPLATE` is a separate `SimpleUnboundProtocol<..., FriendlyByteBuf>`. We use the bound
`CLIENTBOUND`.)

Note on generics: the three packet types span two listener hierarchies —
`ClientboundUpdateEnabledFeaturesPacket` and `ClientboundRegistryDataPacket` /
`ClientboundSelectKnownPacks` are `Packet<ClientConfigurationPacketListener>`, while
`ClientboundUpdateTagsPacket` lives in `net.minecraft.network.protocol.common` and is
`Packet<ClientCommonPacketListener>` (config extends common, so the CONFIGURATION codec encodes it fine).
A raw/unchecked cast to `Packet` bridges them at the encode call; this is a serialize-only path so the
unchecked cast is safe (the codec dispatches on the packet's `type()`, not the static generic).

---

## WORKING recipes (compiled + ran on Paper 1.21.5)

For a joined player's `ServerPlayer sp`, `MinecraftServer server = sp.getServer()`,
`LayeredRegistryAccess<RegistryLayer> registries = server.registries()`.

All three mirror what vanilla `ServerConfigurationPacketListenerImpl.startConfiguration()` does (verified
by decompiling that method's bytecode).

### 1. Enabled features — `id=12`, 20 bytes

```java
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.world.flag.FeatureFlags;

new ClientboundUpdateEnabledFeaturesPacket(
        FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures()));
```

- `WorldData.enabledFeatures()` → `FeatureFlagSet` (the world's actually-enabled flags).
- `FeatureFlags.REGISTRY.toNames(FeatureFlagSet)` → `Set<ResourceLocation>` (the packet's sole field).
- This is byte-for-byte how vanilla `startConfiguration` builds it.

### 2. Registry data — 21 packets, `id=7`, ~120 KB total (the big one)

This needs TWO calls. `SynchronizeRegistriesTask.start(sink)` only sends `ClientboundSelectKnownPacks`
and then awaits the client's `ServerboundSelectKnownPacks` ack before sending registries. With no client,
drive the response ourselves (Arcade's documented trick):

```java
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.server.packs.repository.KnownPack;

// requestedPacks: exactly how startConfiguration computes them.
List<KnownPack> requestedPacks =
        server.getResourceManager().listPacks()
              .flatMap(p -> p.knownPackInfo().stream())   // PackResources.knownPackInfo(): Optional<KnownPack>
              .toList();

SynchronizeRegistriesTask task = new SynchronizeRegistriesTask(requestedPacks, registries);

// start() -> emits ONE ClientboundSelectKnownPacks(requestedPacks) (id=14, 24 B). Capture it
// (Flashback includes it in the config phase), but the registry data is NOT here yet.
task.start(selectKnownPacksSink::add);

// Drive the ack ourselves. handleResponse(clientPacks, sink): if clientPacks.equals(requestedPacks)
// it sends registries that REFERENCE the known packs (small); otherwise it calls
// sendRegistries(sink, Set.of()) which emits the FULL INLINE ClientboundRegistryDataPacket per
// registry. Passing List.of() (a fresh client with no local packs) forces the full inline data —
// which is what a renderable snapshot needs (the client may not have the server's packs).
task.handleResponse(List.of(), registryDataSink::add);   // -> 21 × ClientboundRegistryDataPacket (id=7)
```

The 2-arg ctor is `SynchronizeRegistriesTask(List<KnownPack> requestedPacks,
LayeredRegistryAccess<RegistryLayer> registries)`. Both `start` and `handleResponse` take a
`Consumer<Packet<?>>`. On a default flat world this emits **21 registry-data packets** (one per
synced registry — biomes, dimension types, wolf/cat/pig variants, chat types, painting variants,
banner patterns, damage types, trim materials/patterns, etc.), the largest ~30 KB and ~24.6 KB,
**~120 KB total**. Registry data dominates the config phase size, as expected.

> Decision for production: pass `List.of()` to `handleResponse` to force **full inline** registry data,
> so the snapshot is self-contained and renderable regardless of what packs the replay client has. If we
> ever want the smaller "by-reference" form we'd pass `requestedPacks` and the client would need those
> vanilla packs locally — riskier for a portable `.flashback`. Full inline is the safe default.

### 3. Tags — `id=13`, 27199 bytes

```java
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.tags.TagNetworkSerialization;

new ClientboundUpdateTagsPacket(
        TagNetworkSerialization.serializeTagsToNetwork(registries));
```

- `TagNetworkSerialization.serializeTagsToNetwork(LayeredRegistryAccess<RegistryLayer>)` →
  `Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload>` (one tag-payload per
  registry). It takes the `LayeredRegistryAccess` directly (i.e. `server.registries()`).
- The packet ctor takes exactly that map. It's a `common`-package packet (`Packet<ClientCommonPacketListener>`);
  encoded via the CONFIGURATION codec without issue.

---

## Live version accessors (compiled, observed values)

```java
import net.minecraft.SharedConstants;

int protocol = SharedConstants.getProtocolVersion();                              // -> 770
int data     = SharedConstants.getCurrentVersion().getDataVersion().getVersion(); // -> 4325
```

- `SharedConstants.getProtocolVersion()` → `int` = **770** (1.21.5 protocol).
- `SharedConstants.getCurrentVersion()` → `WorldVersion`; `.getDataVersion()` → `DataVersion`;
  `.getVersion()` → `int` = **4325** (1.21.5 data version). (It is `.getVersion()`, NOT `.dataVersion()`;
  `WorldVersion.getDataVersion()` returns a `DataVersion` whose `getVersion()` is the int.)

---

## Observed packet counts / sizes (real run)

| Config packet | id | count | bytes |
|---|---:|---:|---|
| `ClientboundUpdateEnabledFeaturesPacket` | 12 | 1 | 20 |
| `ClientboundSelectKnownPacks` (from `start()`) | 14 | 1 | 24 |
| `ClientboundRegistryDataPacket` (from `handleResponse(List.of())`) | 7 | 21 | 94 .. 29952, **~120 057 total** |
| `ClientboundUpdateTagsPacket` | 13 | 1 | 27 199 |

Total config-phase ≈ **147 KB** for a default flat world. Registry data + tags dominate; this confirms
the value of de-duplicating / caching config-phase bytes (they are session-invariant for a given
server config + datapacks).

---

## Notes for implementation

- **Ordering** (match a fresh client / Flashback `writeSnapshot`): enabled-features →
  select-known-packs → registry-data (×N) → tags. Then the PLAY phase (login, create_local_player, …).
  Whether to include `ClientboundSelectKnownPacks` in the snapshot: Flashback's config phase records all
  config-phase clientbound packets, and the real client does receive select-known-packs, so include it.
- **One codec instance** (`ConfigurationProtocols.CLIENTBOUND.codec()`) serializes every config packet —
  no per-player binding, no registry decorator (contrast PLAY which binds per `RegistryAccess`).
- **Threading**: the probe ran on the player's entity scheduler (Folia-safe / main thread). Registry &
  tag reads touch `server.registries()` which is stable; safe on the server thread. Reuse
  `PlatformScheduler`.
- **Version coupling (pin per MC version)**: `SynchronizeRegistriesTask(List<KnownPack>,
  LayeredRegistryAccess<RegistryLayer>)` 2-arg ctor; `start(Consumer<Packet<?>>)` +
  `handleResponse(List<KnownPack>, Consumer<Packet<?>>)`; `PackResources.knownPackInfo()`;
  `FeatureFlags.REGISTRY.toNames(...)` + `WorldData.enabledFeatures()`;
  `TagNetworkSerialization.serializeTagsToNetwork(LayeredRegistryAccess)`;
  `ConfigurationProtocols.CLIENTBOUND.codec()`; `SharedConstants.getProtocolVersion()` /
  `getCurrentVersion().getDataVersion().getVersion()`. All resolved at runtime under Paper's
  PluginRemapper (Mojmap source → Spigot runtime) and ran clean.

## Fallback (NOT needed)

The Arcade fallback — caching the REAL config packets by injecting a raw capture handler at the config
phase — is **not required**: synthesis via `SynchronizeRegistriesTask` + the two helper calls produces
the full, correct, renderable config-phase byte stream with no client and no early Netty inject. (Config
data is session-invariant, so even if we later wanted byte-authentic capture, the synthesized form is
equivalent in shape and self-contained.) Not pursued.

## Reproduction

Temporary `F1ConfigProbe` (`src/main/java/.../snapshot/F1ConfigProbe.java`) + `F1SpikeIT`
(`src/test/java/.../harness/F1SpikeIT.java`) + a one-shot `PlayerJoinEvent` probe block in
`FlashbackServerPlugin.onEnable` produced the `[cfg]`/`[ver]` log above on a real Paper 1.21.5 boot with
an MCProtocolLib bot. All removed after the spike; this doc is the artifact. `./gradlew build` and
`./gradlew test` green; existing ITs compile.
