package dev.zeffut.flashbackserver.capture;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class CaptureListener implements Listener {
    private static final Logger LOG = Logger.getLogger("FlashbackServer");
    private final ConcurrentHashMap<UUID, AtomicLong> counts = new ConcurrentHashMap<>();

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        AtomicLong counter = counts.computeIfAbsent(player.getUniqueId(), k -> new AtomicLong());
        PacketCapture.inject(player, (p, packet) -> counter.incrementAndGet());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        PacketCapture.eject(player);
        // Stable log line parsed by the integration test — keep the format.
        LOG.info("[capture] " + player.getName() + " packets=" + capturedCount(player.getUniqueId()));
    }

    /** Number of outbound packets captured for the given player since join. */
    public long capturedCount(UUID playerId) {
        AtomicLong c = counts.get(playerId);
        return c == null ? 0 : c.get();
    }
}
