package dev.zeffut.flashbackserver.command;

import dev.zeffut.flashbackserver.clip.ClipManager;
import dev.zeffut.flashbackserver.record.RecordingManager;
import dev.zeffut.flashbackserver.verify.ReplayVerifier;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public final class ReplayCommand implements CommandExecutor {
    private static final List<String> HELP_LINES = List.of(
            "/replay start players <player>   - start recording a player",
            "/replay stop players <player>    - stop & save a recording",
            "/replay clip arm <player>        - start a rolling clip buffer",
            "/replay clip disarm <player>     - stop the clip buffer",
            "/replay clip save <player>       - save the current clip",
            "/replay verify <file>            - decode-check a saved replay",
            "/replay help                     - this help"
    );

    private final RecordingManager manager;
    private final ClipManager clipManager;
    private final Path replaysDir;
    private final Path clipsDir;
    private final Logger logger;
    private final Plugin plugin;

    public ReplayCommand(
            RecordingManager manager,
            ClipManager clipManager,
            Path replaysDir,
            Path clipsDir,
            Logger logger,
            Plugin plugin) {
        this.manager = manager;
        this.clipManager = clipManager;
        this.replaysDir = replaysDir;
        this.clipsDir = clipsDir;
        this.logger = logger;
        this.plugin = plugin;
    }

    private void sendHelp(CommandSender sender) {
        for (String line : HELP_LINES) {
            sender.sendMessage(line);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("flashbackserver.replay")) {
            sender.sendMessage("You don't have permission to use /replay.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0];

        if (sub.equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        if (sub.equalsIgnoreCase("start") || sub.equalsIgnoreCase("stop")) {
            if (args.length != 3 || !args[1].equalsIgnoreCase("players")) {
                sendHelp(sender);
                return true;
            }
            Player target = sender.getServer().getPlayerExact(args[2]);
            if (target == null) { sender.sendMessage("Unknown player: " + args[2]); return true; }
            if (sub.equalsIgnoreCase("start")) {
                try {
                    sender.sendMessage(manager.start(target) ? "Recording " + target.getName()
                        : target.getName() + " is already being recorded");
                } catch (Exception e) {
                    sender.sendMessage("Replay error: " + e);
                }
            } else {
                if (!manager.isRecording(target)) {
                    sender.sendMessage(target.getName() + " was not being recorded");
                } else {
                    sender.sendMessage("Stopping recording for " + target.getName() + " (writing replay…)");
                    var future = manager.stop(target);
                    future.whenComplete((path, err) -> sender.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                        if (err != null) sender.sendMessage("Replay error: " + err);
                        else if (path != null) sender.sendMessage("Saved: " + path.getFileName());
                    }));
                }
            }

        } else if (sub.equalsIgnoreCase("clip")) {
            if (args.length != 3) {
                sendHelp(sender);
                return true;
            }
            String action = args[1];
            String name = args[2];
            Player target = sender.getServer().getPlayerExact(name);
            if (target == null) { sender.sendMessage("Unknown player: " + name); return true; }

            if (action.equalsIgnoreCase("arm")) {
                if (clipManager.arm(target)) {
                    sender.sendMessage("Armed clips for " + target.getName());
                } else {
                    sender.sendMessage(target.getName() + " already has clips armed");
                }
            } else if (action.equalsIgnoreCase("disarm")) {
                if (clipManager.disarm(target)) {
                    sender.sendMessage("Disarmed clips for " + target.getName());
                } else {
                    sender.sendMessage(target.getName() + " had no clips armed");
                }
            } else if (action.equalsIgnoreCase("save")) {
                if (!clipManager.isArmed(target)) {
                    sender.sendMessage(target.getName() + " has no clips armed");
                } else {
                    sender.sendMessage("Saving clip for " + target.getName() + "…");
                    var future = clipManager.saveClip(target);
                    future.whenComplete((path, err) -> sender.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                        if (err != null) sender.sendMessage("Replay error: " + err);
                        else if (path != null) sender.sendMessage("Saved: " + path.getFileName());
                    }));
                }
            } else {
                sendHelp(sender);
            }

        } else if (sub.equalsIgnoreCase("verify")) {
            if (args.length != 2) {
                sendHelp(sender);
                return true;
            }
            String filename = args[1];
            Path target = resolveReplayFile(filename);
            if (target == null) {
                sender.sendMessage("Unknown replay file: " + filename);
                return true;
            }

            ReplayVerifier.Result result =
                    ReplayVerifier.verify(target, VersionAdapters.current());

            String summary = "Verify " + filename + ": decoded=" + result.decoded()
                    + " errors=" + result.errors();
            logger.info(summary);
            sender.sendMessage(summary);

            if (result.errors() > 0) {
                List<String> probs = result.problems();
                int limit = Math.min(probs.size(), 5);
                for (int i = 0; i < limit; i++) {
                    logger.info("Verify problem: " + probs.get(i));
                    sender.sendMessage("Verify problem: " + probs.get(i));
                }
            }

        } else {
            sendHelp(sender);
        }

        return true;
    }

    /**
     * Resolves {@code filename} under replaysDir first, then clipsDir, refusing any path that escapes
     * those directories (no path traversal). Returns {@code null} if not found.
     */
    private Path resolveReplayFile(String filename) {
        Path inReplays = resolveWithin(replaysDir, filename);
        if (inReplays != null) return inReplays;
        return resolveWithin(clipsDir, filename);
    }

    private static Path resolveWithin(Path dir, String filename) {
        Path base = dir.normalize();
        Path candidate = base.resolve(filename).normalize();
        if (!candidate.startsWith(base)) return null; // path traversal attempt
        return java.nio.file.Files.exists(candidate) ? candidate : null;
    }
}
