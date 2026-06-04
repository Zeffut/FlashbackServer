package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.capture.PacketCapture;
import dev.zeffut.flashbackserver.capture.PacketSink;
import dev.zeffut.flashbackserver.platform.PlatformScheduler;
import dev.zeffut.flashbackserver.snapshot.SnapshotBuilder;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import dev.zeffut.flashbackserver.telemetry.Telemetry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class RecordingManager implements Listener {

    private final Plugin plugin;
    private final Path outputDir;
    private final Telemetry telemetry;
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();
    private record Active(FlashbackRecorder recorder, TickClock clock, Path output, PacketSink sink) {}

    public RecordingManager(Plugin plugin, Path outputDir, Telemetry telemetry) {
        this.plugin = plugin;
        this.outputDir = outputDir;
        this.telemetry = telemetry;
    }

    public boolean start(Player player) {
        UUID id = player.getUniqueId();
        if (active.containsKey(id)) return false;
        Path out = outputDir.resolve(player.getName() + "-" + id + ".flashback");
        var adapter = VersionAdapters.current();
        FlashbackRecorder recorder = new FlashbackRecorder(out, player.getName(),
            adapter.protocolVersion(), adapter.dataVersion());
        TickClock clock = new TickClock(plugin, player);
        PacketSink sink = (p, packet) -> {
            if (packet.rawBytes() != null) recorder.onPacket(packet.rawBytes());
        };
        if (active.putIfAbsent(id, new Active(recorder, clock, out, sink)) != null) return false;
        PacketCapture.injectRaw(player, sink);
        clock.start(recorder::onTick);

        // Build the initial-state snapshot on the player's region thread (Folia) / main thread (Paper).
        // The capture can run immediately; the snapshot just needs to be set before stop() writes.
        player.getScheduler().run(plugin, t -> {
            try {
                recorder.setSnapshot(SnapshotBuilder.build(player));
            } catch (Exception e) {
                plugin.getLogger().severe("Snapshot build failed for " + player.getName()
                        + " — this recording will NOT be renderable: " + e.getMessage());
            }
        }, null);

        return true;
    }

    public CompletableFuture<Path> stop(Player player) {
        Active a = active.remove(player.getUniqueId());
        var future = new CompletableFuture<Path>();
        if (a == null) { future.complete(null); return future; }
        PacketCapture.ejectRaw(player, a.sink());
        a.clock().stop();
        PlatformScheduler.async(plugin, () -> {
            try {
                a.recorder().stop();                 // file write, off the server threads
                plugin.getLogger().info("Saved replay: " + a.output());
                long fileBytes = -1;
                try { fileBytes = Files.size(a.output()); } catch (Exception ignored) {}
                telemetry.capture("recording_saved", Map.of("file_bytes", fileBytes));
                future.complete(a.output());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write replay: " + e.getMessage());
                telemetry.capture("recording_failed", Map.of("reason_class", e.getClass().getSimpleName()));
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public boolean isRecording(Player player) { return active.containsKey(player.getUniqueId()); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Active a = active.get(event.getPlayer().getUniqueId());
        if (a != null) a.recorder().rollChunk();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!isRecording(player)) return;
        stop(player); // fire-and-forget; logs "Saved replay:" on success
    }
}
