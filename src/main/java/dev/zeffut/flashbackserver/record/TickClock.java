package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.platform.PlatformScheduler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Fires a callback once per server tick tied to a player's region (Folia-safe). */
public final class TickClock {
    private final Plugin plugin;
    private final Player player;
    private Runnable cancel;

    public TickClock(Plugin plugin, Player player) { this.plugin = plugin; this.player = player; }

    public void start(Runnable onTick) {
        cancel = PlatformScheduler.repeatForEntity(plugin, player, 1L, onTick);
    }

    public void stop() { if (cancel != null) { cancel.run(); cancel = null; } }
}
