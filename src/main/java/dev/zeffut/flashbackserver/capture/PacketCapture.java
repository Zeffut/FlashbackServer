package dev.zeffut.flashbackserver.capture;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

public final class PacketCapture {
    private static final String HANDLER_NAME = "flashback_capture";

    private PacketCapture() {}

    /** Injects a capturing handler before the encoder; forwards each outbound packet to the sink. */
    public static void inject(Player player, PacketSink sink) {
        Channel channel = ChannelAccess.of(player);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) return; // idempotent
            channel.pipeline().addBefore("encoder", HANDLER_NAME, new ChannelDuplexHandler() {
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
        });
    }

    /** Removes the capturing handler if present. Safe to call on quit/disable. */
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
        });
    }
}
