# Paper 1.21.5 Outbound Netty Pipeline

Observed on a live Paper 1.21.5-114 server (build `a1b3058`, 2025-06-18) via the
`PipelineSpikeIT` integration test (see commit history). All findings are from real
runtime output — not guesses.

---

## Channel accessor

The Mojang-mapped path compiled and worked directly at runtime (no reflection needed):

```java
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.craftbukkit.entity.CraftPlayer;

CraftPlayer craftPlayer = (CraftPlayer) player;          // org.bukkit.craftbukkit.entity.CraftPlayer
ServerPlayer serverPlayer = craftPlayer.getHandle();     // net.minecraft.server.level.ServerPlayer
ServerGamePacketListenerImpl gameListener = serverPlayer.connection;
// ^ public field declared in ServerPlayer
// ServerGamePacketListenerImpl extends ServerCommonPacketListenerImpl which has:
//   public final net.minecraft.network.Connection connection;
Connection connection = gameListener.connection;
io.netty.channel.Channel channel = connection.channel;  // public field on Connection
```

Channel implementation at runtime: `io.netty.channel.socket.nio.NioSocketChannel`

---

## Outbound handler names (in order)

Observed from `channel.pipeline().names()` at the `PlayerJoinEvent` moment, after
the play-phase pipeline is fully assembled:

```
FlushConsolidationHandler#0
timeout
splitter
decompress
FlowControlHandler#0
decoder
prepender
compress
encoder
unbundler
hackfix
packet_handler
DefaultChannelPipeline$TailContext#0
```

Direction notes:
- **Inbound** (server ← client): `splitter` → `decompress` → `FlowControlHandler#0` → `decoder` → `packet_handler`
- **Outbound** (server → client): `packet_handler` → `unbundler` → `hackfix` → `encoder` → `compress` → `prepender` → `FlushConsolidationHandler#0` → socket

---

## Form of outbound messages at a tail-injected duplex handler

A `ChannelDuplexHandler` added with `addLast("flashback-probe", ...)` sits between
`compress` and `prepender` in outbound order (i.e., **after** `packet_handler` but
**before** the encoder). Messages seen in `write(ctx, msg, promise)` are full
Minecraft `Packet<?>` objects — **not** `ByteBuf`:

```
net.minecraft.network.protocol.game.ClientboundSystemChatPacket
net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket
net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket
net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket
net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket
net.minecraft.network.protocol.game.ClientboundSetTimePacket
net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket
net.minecraft.network.protocol.game.ClientboundGameEventPacket
net.minecraft.network.protocol.game.ClientboundTickingStatePacket
net.minecraft.network.protocol.game.ClientboundTickingStepPacket
net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
```

All are concrete implementations of `net.minecraft.network.protocol.Packet<?>`.

---

## Chosen injection point for capture

**Inject `addBefore("encoder", "flashback-capture", handler)`.**

Rationale:
- At this point messages are still `Packet<?>` objects (rich structured data), not
  yet serialised `ByteBuf`s.
- The `encoder` handler serialises the packet to a `ByteBuf` (with packet id prefix),
  then `compress` compresses, then `prepender` prepends the length.
- Injecting *before* `encoder` lets us inspect packet type and fields with zero
  extra deserialisation cost.
- For Flashback recording we want `id + payload bytes`. Two options:
  1. **Intercept at `addBefore("encoder")` as Packet objects**, then use Paper's own
     codec to serialise to bytes (most robust; survives obfuscation changes within a
     Mojang-mapped build).
  2. **Intercept at `addAfter("encoder")` as encoded ByteBuf**, read the id-prefixed
     bytes directly. Simpler for raw capture but the buf is not yet compressed/framed,
     so we get the raw id+payload which is exactly what Flashback needs.
- **Recommended: `addBefore("encoder")` capturing Packet objects.** This is the
  highest-fidelity point and avoids the need to copy/slice ByteBufs.

---

## Mapping note

The plugin was compiled Mojang-mapped (paperweight-userdev `2.0.0-beta.18` with
`paperDevBundle("1.21.5-R0.1-SNAPSHOT")`). The `reobfJar` task produces a
reobfuscated jar for deployment; the log confirms Paper remaps it on load:

```
[PluginRemapper] Remapping plugin 'plugins/FlashbackServer.jar'...
[PluginRemapper] Done remapping plugin 'plugins/FlashbackServer.jar' in 45ms.
```

At runtime the server itself is also remapped (Mojang → obf → Mojang again via
Paper's `ReobfServer` step, confirmed by: `[ReobfServer] Remapping server... Done
remapping server in 2202ms`). The net effect is that Mojang-mapped class names such
as `net.minecraft.network.Connection`, `ServerGamePacketListenerImpl`, and field
names `connection`/`channel` resolve correctly inside the plugin at runtime — no
manual reflection or obfuscation mapping is required.
