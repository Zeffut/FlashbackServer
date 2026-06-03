package dev.zeffut.flashbackserver.capture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.util.NoSuchElementException;
import java.util.logging.Logger;

public final class PacketCapture {
    private static final String HANDLER_NAME = "flashback_capture";
    private static final String RAW_HANDLER_NAME = "flashback_capture_raw";
    private static final String ENCODER = "encoder";
    private static final Logger LOG = Logger.getLogger("FlashbackServer");

    private PacketCapture() {}

    /** Injects a capturing handler before the encoder; forwards each outbound packet to the sink. */
    public static void inject(Player player, PacketSink sink) {
        Channel channel = ChannelAccess.of(player);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) return; // idempotent
            try {
                channel.pipeline().addBefore(ENCODER, HANDLER_NAME, new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        try {
                            sink.accept(player, new CapturedPacket(msg.getClass().getName(), null));
                        } catch (Throwable ignored) {
                            // capture must never break the connection
                        }
                        super.write(ctx, msg, promise);
                    }
                });
            } catch (NoSuchElementException e) {
                // No '"encoder"' handler — likely a Paper pipeline change. Surface it loudly:
                // capture is silently inactive otherwise, which is hard to diagnose.
                LOG.warning("Could not install packet capture for " + player.getName()
                    + ": no '" + ENCODER + "' handler in the Netty pipeline (Paper internals changed?).");
            }
        });
    }

    /**
     * Injects a raw-bytes capturing handler before the encoder. At this pipeline point outbound
     * messages are already-encoded {@link ByteBuf}s containing {@code varint(packetId) + payload}.
     * Each buffer's readable bytes are copied without consuming the original, then forwarded to the
     * sink as {@link CapturedPacket#rawBytes()}.
     */
    public static void injectRaw(Player player, PacketSink sink) {
        Channel channel = ChannelAccess.of(player);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(RAW_HANDLER_NAME) != null) return; // idempotent
            try {
                channel.pipeline().addBefore(ENCODER, RAW_HANDLER_NAME, new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        try {
                            if (msg instanceof ByteBuf buf) {
                                byte[] bytes = ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes());
                                sink.accept(player, new CapturedPacket(msg.getClass().getName(), bytes));
                            } else {
                                sink.accept(player, new CapturedPacket(msg.getClass().getName(), null));
                            }
                        } catch (Throwable ignored) {
                            // capture must never break the connection
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

    /** Removes the capturing handler(s) if present. Cleans up both inject and injectRaw modes. Safe to call on quit/disable. */
    public static void eject(Player player) {
        Channel channel;
        try {
            channel = ChannelAccess.of(player);
        } catch (RuntimeException e) {
            return; // player already gone / channel unavailable
        }
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
            if (channel.pipeline().get(RAW_HANDLER_NAME) != null) {
                channel.pipeline().remove(RAW_HANDLER_NAME);
            }
        });
    }
}
