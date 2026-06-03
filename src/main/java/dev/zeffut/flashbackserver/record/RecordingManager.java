package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.capture.PacketCapture;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RecordingManager implements Listener {
    private static final Logger LOG = Logger.getLogger("FlashbackServer");

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
        TickClock clock = new TickClock(plugin);
        active.put(id, new Active(recorder, clock, out));
        PacketCapture.injectRaw(player, (p, packet) -> {
            if (packet.rawBytes() != null) recorder.onPacket(packet.rawBytes());
        });
        clock.start(recorder::onTick);
        return true;
    }

    public Path stop(Player player) throws Exception {
        Active a = active.remove(player.getUniqueId());
        if (a == null) return null;
        PacketCapture.ejectRaw(player);
        a.clock().stop();
        a.recorder().stop();
        return a.output();
    }

    public boolean isRecording(Player player) { return active.containsKey(player.getUniqueId()); }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!isRecording(player)) return;
        try {
            stop(player);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to stop recording for " + player.getName() + " on quit", e);
        }
    }
}
