package dev.zeffut.flashbackserver;

import dev.zeffut.flashbackserver.api.ClipService;
import dev.zeffut.flashbackserver.api.FlashbackAPI;
import dev.zeffut.flashbackserver.api.RecordingService;
import dev.zeffut.flashbackserver.clip.ClipDeathListener;
import dev.zeffut.flashbackserver.clip.ClipManager;
import dev.zeffut.flashbackserver.command.ReplayCommand;
import dev.zeffut.flashbackserver.command.ReplayTabCompleter;
import dev.zeffut.flashbackserver.record.RecordingManager;
import dev.zeffut.flashbackserver.telemetry.Telemetry;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class FlashbackServerPlugin extends JavaPlugin {

    private volatile RecordingManager recordingManager;
    private volatile ClipManager clipManager;

    @Override
    public void onEnable() {
        java.nio.file.Path replays = getDataFolder().toPath().resolve("replays");
        try { java.nio.file.Files.createDirectories(replays); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        // Eagerly select + instantiate the version adapter so any version mismatch surfaces at enable
        // (fail-fast) and the chosen adapter is observable.
        dev.zeffut.flashbackserver.version.VersionAdapter adapter =
            dev.zeffut.flashbackserver.version.VersionAdapters.current();
        getLogger().info("Version adapter: " + adapter.getClass().getSimpleName()
            + " (protocol " + adapter.protocolVersion() + ", data " + adapter.dataVersion() + ")");

        saveDefaultConfig();
        int window = getConfig().getInt("clips.window-seconds", 30);
        boolean autoClip = getConfig().getBoolean("clips.auto-clip-on-death", true);
        java.nio.file.Path clipsDir = getDataFolder().toPath().resolve("clips");
        try { java.nio.file.Files.createDirectories(clipsDir); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        boolean telemetryEnabled = getConfig().getBoolean("telemetry.enabled", true);
        String phHost = getConfig().getString("telemetry.posthog.host", "https://us.i.posthog.com");
        String phKey = getConfig().getString("telemetry.posthog.project-key", "");
        Telemetry telemetry = new Telemetry(telemetryEnabled, phHost, phKey,
            Telemetry.loadOrCreateDistinctId(getDataFolder().toPath()), getPluginMeta().getVersion(), getLogger());
        if (telemetry.isEnabled()) {
            getLogger().info("Anonymous telemetry is enabled (no player data). Disable it with telemetry.enabled: false in config.yml.");
        }
        telemetry.capture("plugin_enabled", java.util.Map.of(
            "platform", isFolia() ? "Folia" : "Paper",
            "server_version", getServer().getVersion(),
            "mc_version", getServer().getMinecraftVersion(),
            "plugin_version", getPluginMeta().getVersion()));

        RecordingManager manager = new RecordingManager(this, replays, telemetry);
        getServer().getPluginManager().registerEvents(manager, this);

        ClipManager clips =
            new ClipManager(this, clipsDir, window, telemetry);
        getServer().getPluginManager().registerEvents(clips, this);
        getServer().getPluginManager().registerEvents(
            new ClipDeathListener(clips, autoClip), this);
        PluginCommand replayCmd = getCommand("replay");
        replayCmd.setExecutor(
            new ReplayCommand(
                manager,
                clips,
                replays,
                clipsDir,
                getLogger(),
                this));
        replayCmd.setTabCompleter(
            new ReplayTabCompleter(replays, clipsDir));

        this.recordingManager = manager;
        this.clipManager = clips;
        getServer().getServicesManager().register(
            RecordingService.class, manager, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
            ClipService.class, clips, this, ServicePriority.Normal);
        FlashbackAPI.bind(this, manager, clips);
        getLogger().info("Plugin API registered: RecordingService, ClipService");

        getLogger().info("FlashbackServer enabled.");
    }

    @Override
    public void onDisable() {
        int recordings = recordingManager != null ? recordingManager.stopAll() : 0;
        int clips = clipManager != null ? clipManager.disarmAll() : 0;
        if (recordings > 0 || clips > 0) {
            getLogger().info("Disable: closed " + recordings + " recording(s), disarmed "
                    + clips + " clip buffer(s).");
        }
        FlashbackAPI.unbind(this);
        getServer().getServicesManager().unregisterAll(this);
        recordingManager = null;
        clipManager = null;
    }

    /** @return the live recording service, or {@code null} if the plugin is not enabled */
    public RecordingService getRecordingService() {
        return recordingManager;
    }

    /** @return the live clip service, or {@code null} if the plugin is not enabled */
    public ClipService getClipService() {
        return clipManager;
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
