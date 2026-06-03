package dev.zeffut.flashbackserver.capture;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public final class ChannelAccess {
    private ChannelAccess() {}

    /** Returns the player's Netty channel, or throws IllegalStateException if internals differ. */
    public static Channel of(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalStateException("Expected CraftPlayer but got " + player.getClass().getName());
        }
        ServerPlayer handle = craft.getHandle();
        ServerGamePacketListenerImpl gameListener = handle.connection;
        if (gameListener == null) {
            throw new IllegalStateException("ServerGamePacketListenerImpl (handle.connection) was null for player " + player.getName());
        }
        Connection connection = gameListener.connection;
        if (connection == null) {
            throw new IllegalStateException("Connection (gameListener.connection) was null for player " + player.getName());
        }
        Channel channel = connection.channel;
        if (channel == null) {
            throw new IllegalStateException("Netty channel was null for player " + player.getName());
        }
        return channel;
    }
}
