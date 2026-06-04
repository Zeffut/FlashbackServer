package dev.zeffut.flashbackserver.capture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public final class PacketCapture {
    private static final String RAW_HANDLER_NAME = "flashback_capture_raw";
    private static final String ENCODER = "encoder";
    private static final Logger LOG = Logger.getLogger("FlashbackServer");

    /**
     * Per-player list of raw sinks. Keyed by player UUID.
     * Mutations to the list (add/remove) always happen on the channel event loop, matching the
     * handler install/remove lifecycle, so no additional synchronisation is needed beyond
     * CopyOnWriteArrayList for safe iteration during write().
     */
    static final ConcurrentHashMap<UUID, CopyOnWriteArrayList<PacketSink>> RAW_SINKS =
            new ConcurrentHashMap<>();

    private PacketCapture() {}

    /**
     * Adds {@code sink} to the player's raw-sink list. If this is the first sink for that player,
     * installs the {@code flashback_capture_raw} duplex handler which copies the outbound ByteBuf
     * bytes ONCE per packet and fans the copy out to every registered sink. Each sink call is
     * individually guarded so a throwing sink cannot break others or the connection.
     */
    public static void injectRaw(Player player, PacketSink sink) {
        UUID id = player.getUniqueId();
        // Compute the list entry (idempotent on concurrent calls — first one wins the COWAL value)
        CopyOnWriteArrayList<PacketSink> sinks =
                RAW_SINKS.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
        sinks.add(sink);

        Channel channel = ChannelAccess.of(player);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(RAW_HANDLER_NAME) != null) return; // handler already present
            try {
                channel.pipeline().addBefore(ENCODER, RAW_HANDLER_NAME, new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        // Copy bytes ONCE, then fan out to all sinks
                        CopyOnWriteArrayList<PacketSink> current = RAW_SINKS.get(id);
                        if (current != null && !current.isEmpty()) {
                            if (msg instanceof ByteBuf buf) {
                                byte[] bytes = ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes());
                                CapturedPacket pkt = new CapturedPacket(msg.getClass().getName(), bytes);
                                for (PacketSink s : current) {
                                    try {
                                        s.accept(player, pkt);
                                    } catch (Throwable ignored) {
                                        // one bad sink must not affect others or the connection
                                    }
                                }
                            } else {
                                CapturedPacket pkt = new CapturedPacket(msg.getClass().getName(), null);
                                for (PacketSink s : current) {
                                    try {
                                        s.accept(player, pkt);
                                    } catch (Throwable ignored) {}
                                }
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
     * Removes ONLY the given {@code sink} from the player's raw-sink list.
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
        Channel channel;
        try {
            channel = ChannelAccess.of(player);
        } catch (RuntimeException e) {
            return; // player already gone / channel unavailable
        }
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(RAW_HANDLER_NAME) != null) {
                channel.pipeline().remove(RAW_HANDLER_NAME);
            }
        });
    }

    /**
     * Removes ALL raw sinks for the player and the handler (full teardown, e.g. on disconnect).
     * Safe to call if the player is no longer connected.
     */
    public static void ejectRaw(Player player) {
        UUID id = player.getUniqueId();
        RAW_SINKS.remove(id);
        Channel channel;
        try {
            channel = ChannelAccess.of(player);
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
