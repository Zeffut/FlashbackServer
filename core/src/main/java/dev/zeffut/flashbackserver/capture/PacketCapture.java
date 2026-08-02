package dev.zeffut.flashbackserver.capture;

import dev.zeffut.flashbackserver.version.PacketTranslator;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Taps a player's outbound packet stream and hands each recordable packet to every registered sink.
 *
 * <h3>Where the tap sits, and why</h3>
 * The handler is installed with {@code addAfter("encoder", …)}. Netty runs outbound events from the
 * tail towards the head, so a handler on the tail side of the encoder sees each message
 * <em>before</em> it is serialised — as the live NMS packet object rather than a {@link
 * io.netty.buffer.ByteBuf}.
 *
 * <p>That is the difference between a recording that opens and one that does not. The Flashback
 * client refuses 54 clientbound packet types (see {@link RefusedPackets}), and the only way to
 * recognise them is by type. Downstream of the encoder there is nothing left but a numeric id whose
 * meaning changes with the protocol version — which is what an earlier version of this class tried,
 * copying every outbound buffer with no filtering at all.
 *
 * <p>Bundle flattening and encoding are delegated to a per-player {@link PacketTranslator}, so no
 * {@code net.minecraft} type appears here.
 */
public final class PacketCapture {
    private static final String RAW_HANDLER_NAME = "flashback_capture_raw";
    private static final String ENCODER = "encoder";
    private static final Logger LOG = Logger.getLogger("FlashbackServer");

    /**
     * Per-player list of sinks. Keyed by player UUID.
     * Mutations to the list (add/remove) always happen on the channel event loop, matching the
     * handler install/remove lifecycle, so no additional synchronisation is needed beyond
     * CopyOnWriteArrayList for safe iteration during write().
     */
    static final ConcurrentHashMap<UUID, CopyOnWriteArrayList<PacketSink>> RAW_SINKS =
            new ConcurrentHashMap<>();

    private PacketCapture() {}

    /**
     * Adds {@code sink} to the player's sink list. If this is the first sink for that player,
     * installs the {@code flashback_capture_raw} duplex handler, which expands, filters and encodes
     * each outbound packet ONCE and fans the result out to every registered sink. Each sink call is
     * individually guarded so a throwing sink cannot break others or the connection.
     */
    public static void injectRaw(Player player, PacketSink sink) {
        UUID id = player.getUniqueId();
        // Compute the list entry (idempotent on concurrent calls — first one wins the COWAL value)
        CopyOnWriteArrayList<PacketSink> sinks =
                RAW_SINKS.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
        sinks.add(sink);

        Channel channel = VersionAdapters.current().channelOf(player);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(RAW_HANDLER_NAME) != null) return; // handler already present
            // Bound once per recording: building the PLAY codec resolves the player's registry
            // access, which is far too expensive to redo for every packet on a live connection.
            final PacketTranslator translator = VersionAdapters.current().translatorFor(player);
            try {
                channel.pipeline().addAfter(ENCODER, RAW_HANDLER_NAME, new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        CopyOnWriteArrayList<PacketSink> current = RAW_SINKS.get(id);
                        if (current != null && !current.isEmpty()) {
                            try {
                                capture(player, translator, msg, current);
                            } catch (Throwable t) {
                                // Recording must never break the connection it is observing.
                                LOG.warning("Packet capture failed for " + player.getName() + ": "
                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                            }
                        }
                        super.write(ctx, msg, promise);
                    }
                });
            } catch (NoSuchElementException e) {
                LOG.warning("Could not install raw packet capture for " + player.getName()
                    + ": no '" + ENCODER + "' handler in the Netty pipeline (Paper internals changed?).");
            }
        });
    }

    /**
     * Expands one outbound message, drops what the Flashback client refuses, encodes the rest, and
     * fans each survivor out to every sink.
     */
    private static void capture(Player player, PacketTranslator translator, Object msg,
                                List<PacketSink> sinks) {
        for (Object packet : translator.expand(msg)) {
            String className = packet.getClass().getName();
            if (RefusedPackets.isRefused(className)) continue;
            byte[] bytes = translator.encode(packet);
            if (bytes == null || bytes.length == 0) continue;
            CapturedPacket captured = new CapturedPacket(className, bytes);
            for (PacketSink s : sinks) {
                try {
                    s.accept(player, captured);
                } catch (Throwable ignored) {
                    // one bad sink must not affect others or the connection
                }
            }
        }
    }

    /**
     * Removes ONLY the given {@code sink} from the player's sink list.
     * If the list becomes empty, removes the {@code flashback_capture_raw} handler from the pipeline
     * and drops the map entry — the handler is torn down only when the last consumer leaves.
     * Safe to call even if the player is no longer connected.
     */
    public static void ejectRaw(Player player, PacketSink sink) {
        UUID id = player.getUniqueId();
        CopyOnWriteArrayList<PacketSink> sinks = RAW_SINKS.get(id);
        if (sinks == null) return;
        sinks.remove(sink);
        if (!sinks.isEmpty()) return; // other sinks still active — keep the handler

        // Last sink removed: tear down the handler
        RAW_SINKS.remove(id, sinks);
        removeHandler(player);
    }

    /**
     * Removes ALL sinks for the player and the handler (full teardown, e.g. on disconnect).
     * Safe to call if the player is no longer connected.
     */
    public static void ejectRaw(Player player) {
        RAW_SINKS.remove(player.getUniqueId());
        removeHandler(player);
    }

    private static void removeHandler(Player player) {
        Channel channel;
        try {
            channel = VersionAdapters.current().channelOf(player);
        } catch (RuntimeException e) {
            return; // player already gone / channel unavailable
        }
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(RAW_HANDLER_NAME) != null) {
                channel.pipeline().remove(RAW_HANDLER_NAME);
            }
        });
    }
}
