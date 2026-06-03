package dev.zeffut.flashbackserver.record;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class TickClock {
    private final Plugin plugin;
    private BukkitTask task;

    public TickClock(Plugin plugin) { this.plugin = plugin; }

    public void start(Runnable onTick) {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, onTick, 1L, 1L);
    }

    public void stop() { if (task != null) { task.cancel(); task = null; } }
}
