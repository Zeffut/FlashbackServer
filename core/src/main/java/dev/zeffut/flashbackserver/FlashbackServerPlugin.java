package dev.zeffut.flashbackserver;

import dev.zeffut.flashbackserver.command.ReplayCommand;
import dev.zeffut.flashbackserver.record.RecordingManager;
import dev.zeffut.flashbackserver.telemetry.Telemetry;
import org.bukkit.plugin.java.JavaPlugin;

public final class FlashbackServerPlugin extends JavaPlugin {

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

        dev.zeffut.flashbackserver.clip.ClipManager clipManager =
            new dev.zeffut.flashbackserver.clip.ClipManager(this, clipsDir, window, telemetry);
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
                getLogger(),
                this));
        replayCmd.setTabCompleter(
            new dev.zeffut.flashbackserver.command.ReplayTabCompleter(replays, clipsDir));

        getLogger().info("FlashbackServer enabled.");
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
