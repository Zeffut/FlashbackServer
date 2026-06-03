package dev.zeffut.flashbackserver;

import dev.zeffut.flashbackserver.capture.CaptureListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class FlashbackServerPlugin extends JavaPlugin {
    private final CaptureListener captureListener = new CaptureListener();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(captureListener, this);
        getLogger().info("FlashbackServer enabled.");
    }

    public CaptureListener captureListener() {
        return captureListener;
    }
}
