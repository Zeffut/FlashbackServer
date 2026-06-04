package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.capture.PacketCapture;
import dev.zeffut.flashbackserver.capture.PacketSink;
import dev.zeffut.flashbackserver.format.ReplayAction;
import dev.zeffut.flashbackserver.platform.PlatformScheduler;
import dev.zeffut.flashbackserver.record.ReplayFiles;
import dev.zeffut.flashbackserver.record.TickClock;
import dev.zeffut.flashbackserver.snapshot.SnapshotBuilder;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import dev.zeffut.flashbackserver.telemetry.Telemetry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ClipManager implements Listener {
    private final Plugin plugin;
    private final Path outputDir;
    private final int windowSeconds;
    private final Telemetry telemetry;
    private final ConcurrentHashMap<UUID, Armed> armed = new ConcurrentHashMap<>();
    private final AtomicInteger clipCounter = new AtomicInteger();

    private record Armed(
            ClipBuffer buffer,
            TickClock clock,
            PacketSink sink,
            AtomicReference<List<ReplayAction>> cachedConfig,
            AtomicBoolean keyframeBuilding) {}

    public ClipManager(Plugin plugin, Path outputDir, int windowSeconds, Telemetry telemetry) {
        this.plugin = plugin;
        this.outputDir = outputDir;
        this.windowSeconds = windowSeconds;
        this.telemetry = telemetry;
    }

    /** Arms a rolling clip buffer for the player. Returns false if already armed. */
    public boolean arm(Player player) {
        UUID id = player.getUniqueId();
        ClipBuffer buffer = new ClipBuffer(windowSeconds);
        TickClock clock = new TickClock(plugin, player);
        AtomicReference<List<ReplayAction>> cachedConfig = new AtomicReference<>(null);
        AtomicBoolean keyframeBuilding = new AtomicBoolean(false);

        PacketSink sink = (p, packet) -> {
            if (packet.rawBytes() != null) buffer.onPacket(packet.rawBytes());
        };

        // Tick callback: advance buffer clock; when a new keyframe is due, build it on the
        // player's region thread (guards concurrent builds with keyframeBuilding).
        Runnable tickCallback = () -> {
            buffer.onTick();
            if (buffer.needsKeyframe() && keyframeBuilding.compareAndSet(false, true)) {
                player.getScheduler().run(plugin, t -> {
                    try {
                        buffer.setKeyframe(SnapshotBuilder.dynamicActions(player));
                    } finally {
                        keyframeBuilding.set(false);
                    }
                }, null);
            }
        };

        Armed entry = new Armed(buffer, clock, sink, cachedConfig, keyframeBuilding);
        if (armed.putIfAbsent(id, entry) != null) return false; // already armed

        PacketCapture.injectRaw(player, sink);
        clock.start(tickCallback);

        // Seed config + first keyframe on the player's region thread.
        player.getScheduler().run(plugin, t -> {
            try {
                cachedConfig.set(SnapshotBuilder.configActions(player));
                buffer.setKeyframe(SnapshotBuilder.dynamicActions(player));
            } catch (Exception e) {
                plugin.getLogger().warning("SnapshotBuilder failed for " + player.getName()
                        + " — clip will have empty snapshot: " + e.getMessage());
            }
        }, null);

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

        List<ReplayAction> config = a.cachedConfig().get();
        if (config == null) {
            // Region task hasn't run yet — clip would be non-renderable; skip gracefully.
            plugin.getLogger().warning("Clip not ready yet for " + player.getName());
            future.complete(null);
            return future;
        }

        // Capture snapshot keyframe + stream + tick count atomically (one lock) so a concurrent
        // keyframe rotation can't misalign them.
        ClipBuffer.ClipData clip = a.buffer().captureClip();
        List<ReplayAction> snapshot = new ArrayList<>(config);
        snapshot.addAll(clip.snapshot());
        List<ReplayAction> stream = clip.stream();
        int ticks = clip.tickCount();
        String name = player.getName();
        Path out = outputDir.resolve(name + "-clip-" + clipCounter.incrementAndGet() + ".flashback");
        PlatformScheduler.async(plugin, () -> {
            try {
                var adapter = VersionAdapters.current();
                ReplayFiles.write(out, name, adapter.protocolVersion(), adapter.dataVersion(),
                    snapshot, stream, ticks);
                plugin.getLogger().info("Saved clip: " + out);
                long fileBytes = -1;
                try { fileBytes = Files.size(out); } catch (Exception ignored) {}
                telemetry.capture("clip_saved", Map.of("file_bytes", fileBytes));
                future.complete(out);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write clip: " + e.getMessage());
                telemetry.capture("clip_failed", Map.of("reason_class", e.getClass().getSimpleName()));
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
