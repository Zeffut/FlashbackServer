package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.capture.PacketCapture;
import dev.zeffut.flashbackserver.capture.PacketSink;
import dev.zeffut.flashbackserver.format.ReplayAction;
import dev.zeffut.flashbackserver.platform.PlatformScheduler;
import dev.zeffut.flashbackserver.record.ReplayFiles;
import dev.zeffut.flashbackserver.record.TickClock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClipManager implements Listener {
    private final Plugin plugin;
    private final Path outputDir;
    private final int windowSeconds;
    private final ConcurrentHashMap<UUID, Armed> armed = new ConcurrentHashMap<>();
    private final AtomicInteger clipCounter = new AtomicInteger();

    private record Armed(ClipBuffer buffer, TickClock clock, PacketSink sink) {}

    public ClipManager(Plugin plugin, Path outputDir, int windowSeconds) {
        this.plugin = plugin;
        this.outputDir = outputDir;
        this.windowSeconds = windowSeconds;
    }

    /** Arms a rolling clip buffer for the player. Returns false if already armed. */
    public boolean arm(Player player) {
        UUID id = player.getUniqueId();
        ClipBuffer buffer = new ClipBuffer(windowSeconds);
        TickClock clock = new TickClock(plugin, player);
        PacketSink sink = (p, packet) -> {
            if (packet.rawBytes() != null) buffer.onPacket(packet.rawBytes());
        };
        Armed entry = new Armed(buffer, clock, sink);
        if (armed.putIfAbsent(id, entry) != null) return false; // already armed
        PacketCapture.injectRaw(player, sink);
        clock.start(buffer::onTick);
        return true;
    }

    /** Disarms (stops buffering). Returns false if not armed. */
    public boolean disarm(Player player) {
        Armed a = armed.remove(player.getUniqueId());
        if (a == null) return false;
        PacketCapture.ejectRaw(player, a.sink());
        a.clock().stop();
        return true;
    }

    public boolean isArmed(Player player) { return armed.containsKey(player.getUniqueId()); }

    /** Writes the player's current clip window to disk async. Future completes with the path (null if not armed). */
    public CompletableFuture<Path> saveClip(Player player) {
        Armed a = armed.get(player.getUniqueId());
        CompletableFuture<Path> future = new CompletableFuture<>();
        if (a == null) { future.complete(null); return future; }
        List<ReplayAction> actions = a.buffer().snapshotClip();
        int ticks = a.buffer().tickCount();
        String name = player.getName();
        Path out = outputDir.resolve(name + "-clip-" + clipCounter.incrementAndGet() + ".flashback");
        PlatformScheduler.async(plugin, () -> {
            try {
                ReplayFiles.write(out, name, 769, 4189, actions, ticks);
                plugin.getLogger().info("Saved clip: " + out);
                future.complete(out);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write clip for " + name + ": " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isArmed(player)) {
            disarm(player);
        }
    }
}
