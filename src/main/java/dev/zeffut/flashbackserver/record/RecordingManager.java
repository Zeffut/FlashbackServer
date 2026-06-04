package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.capture.PacketCapture;
import dev.zeffut.flashbackserver.platform.PlatformScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class RecordingManager implements Listener {

    private final Plugin plugin;
    private final Path outputDir;
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();
    private record Active(FlashbackRecorder recorder, TickClock clock, Path output) {}

    public RecordingManager(Plugin plugin, Path outputDir) {
        this.plugin = plugin; this.outputDir = outputDir;
    }

    public boolean start(Player player) {
        UUID id = player.getUniqueId();
        if (active.containsKey(id)) return false;
        Path out = outputDir.resolve(player.getName() + "-" + id + ".flashback");
        FlashbackRecorder recorder = new FlashbackRecorder(out, player.getName(), 769, 4189);
        TickClock clock = new TickClock(plugin, player);
        active.put(id, new Active(recorder, clock, out));
        PacketCapture.injectRaw(player, (p, packet) -> {
            if (packet.rawBytes() != null) recorder.onPacket(packet.rawBytes());
        });
        clock.start(recorder::onTick);
        return true;
    }

    public CompletableFuture<Path> stop(Player player) {
        Active a = active.remove(player.getUniqueId());
        var future = new CompletableFuture<Path>();
        if (a == null) { future.complete(null); return future; }
        PacketCapture.ejectRaw(player);
        a.clock().stop();
        PlatformScheduler.async(plugin, () -> {
            try {
                a.recorder().stop();                 // file write, off the server threads
                plugin.getLogger().info("Saved replay: " + a.output());
                future.complete(a.output());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write replay for " + player.getName() + ": " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public boolean isRecording(Player player) { return active.containsKey(player.getUniqueId()); }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!isRecording(player)) return;
        stop(player); // fire-and-forget; logs "Saved replay:" on success
    }
}
