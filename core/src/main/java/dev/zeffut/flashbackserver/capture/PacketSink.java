package dev.zeffut.flashbackserver.capture;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface PacketSink {
    void accept(Player player, CapturedPacket packet);
}
