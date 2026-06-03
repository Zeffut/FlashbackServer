package dev.zeffut.flashbackserver;

import dev.zeffut.flashbackserver.capture.CaptureListener;
import dev.zeffut.flashbackserver.command.ReplayCommand;
import dev.zeffut.flashbackserver.record.RecordingManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class FlashbackServerPlugin extends JavaPlugin {
    private final CaptureListener captureListener = new CaptureListener();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(captureListener, this);

        java.nio.file.Path replays = getDataFolder().toPath().resolve("replays");
        try { java.nio.file.Files.createDirectories(replays); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        RecordingManager manager = new RecordingManager(this, replays);
        getServer().getPluginManager().registerEvents(manager, this);
        getCommand("replay").setExecutor(new ReplayCommand(manager));

        getLogger().info("FlashbackServer enabled.");
    }

    public CaptureListener captureListener() {
        return captureListener;
    }
}
