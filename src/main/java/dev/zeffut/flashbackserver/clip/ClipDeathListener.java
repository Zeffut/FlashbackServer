package dev.zeffut.flashbackserver.clip;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** Auto-saves a clip when an armed player dies (if enabled). */
public final class ClipDeathListener implements Listener {
    private final ClipManager clips;
    private final boolean enabled;

    public ClipDeathListener(ClipManager clips, boolean enabled) {
        this.clips = clips;
        this.enabled = enabled;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled) return;
        var player = event.getEntity(); // PlayerDeathEvent#getEntity() returns the Player
        if (clips.isArmed(player)) clips.saveClip(player);
    }
}
