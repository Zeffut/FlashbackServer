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

        saveDefaultConfig();
        int window = getConfig().getInt("clips.window-seconds", 30);
        boolean autoClip = getConfig().getBoolean("clips.auto-clip-on-death", true);
        java.nio.file.Path clipsDir = getDataFolder().toPath().resolve("clips");
        try { java.nio.file.Files.createDirectories(clipsDir); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        dev.zeffut.flashbackserver.clip.ClipManager clipManager =
            new dev.zeffut.flashbackserver.clip.ClipManager(this, clipsDir, window);
        getServer().getPluginManager().registerEvents(clipManager, this);
        getServer().getPluginManager().registerEvents(
            new dev.zeffut.flashbackserver.clip.ClipDeathListener(clipManager, autoClip), this);
        org.bukkit.command.PluginCommand replayCmd = getCommand("replay");
        replayCmd.setExecutor(
            new dev.zeffut.flashbackserver.command.ReplayCommand(
                manager,
                clipManager,
                replays,
                clipsDir,
                () -> ((org.bukkit.craftbukkit.CraftServer) getServer()).getServer().registryAccess(),
                getLogger(),
                this));
        replayCmd.setTabCompleter(
            new dev.zeffut.flashbackserver.command.ReplayTabCompleter(replays, clipsDir));

        getLogger().info("FlashbackServer enabled.");
    }

    public CaptureListener captureListener() {
        return captureListener;
    }
}
