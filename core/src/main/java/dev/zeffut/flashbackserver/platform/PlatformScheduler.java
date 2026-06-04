package dev.zeffut.flashbackserver.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

public final class PlatformScheduler {
    private PlatformScheduler() {}

    /**
     * Runs {@code task} every {@code periodTicks} ticks on the entity's region thread (Folia) or the
     * main thread (Paper). Returns a handle that cancels the repeating task.
     */
    public static Runnable repeatForEntity(Plugin plugin, Entity entity, long periodTicks, Runnable task) {
        ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(
                plugin, t -> task.run(), null, 1L, periodTicks);
        return () -> { if (scheduled != null) scheduled.cancel(); };
    }

    /** Runs {@code task} once, off the server threads (async on both Paper and Folia). */
    public static void async(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, (Consumer<ScheduledTask>) t -> task.run());
    }
}
