package dev.zeffut.flashbackserver.command;

import dev.zeffut.flashbackserver.clip.ClipManager;
import dev.zeffut.flashbackserver.record.RecordingManager;
import dev.zeffut.flashbackserver.verify.ReplayDecodeVerifier;
import net.minecraft.core.RegistryAccess;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class ReplayCommand implements CommandExecutor {
    private static final String USAGE =
            "Usage: /replay <start|stop> players <player> | /replay clip <arm|disarm|save> <player> | /replay verify <file>";

    private final RecordingManager manager;
    private final ClipManager clipManager;
    private final Path replaysDir;
    private final Path clipsDir;
    private final Supplier<RegistryAccess> registryAccess;
    private final Logger logger;

    public ReplayCommand(
            RecordingManager manager,
            ClipManager clipManager,
            Path replaysDir,
            Path clipsDir,
            Supplier<RegistryAccess> registryAccess,
            Logger logger) {
        this.manager = manager;
        this.clipManager = clipManager;
        this.replaysDir = replaysDir;
        this.clipsDir = clipsDir;
        this.registryAccess = registryAccess;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }

        String sub = args[0];

        if (sub.equalsIgnoreCase("start") || sub.equalsIgnoreCase("stop")) {
            if (args.length != 3 || !args[1].equalsIgnoreCase("players")) {
                sender.sendMessage(USAGE);
                return true;
            }
            Player target = sender.getServer().getPlayerExact(args[2]);
            if (target == null) { sender.sendMessage("Unknown player: " + args[2]); return true; }
            if (sub.equalsIgnoreCase("start")) {
                try {
                    sender.sendMessage(manager.start(target) ? "Recording " + target.getName()
                        : target.getName() + " is already being recorded");
                } catch (Exception e) {
                    sender.sendMessage("Replay error: " + e.getMessage());
                }
            } else {
                if (!manager.isRecording(target)) {
                    sender.sendMessage(target.getName() + " was not being recorded");
                } else {
                    sender.sendMessage("Stopping recording for " + target.getName() + " (writing replay…)");
                    manager.stop(target); // async; logs "Saved replay: <path>" when the file is on disk
                }
            }

        } else if (sub.equalsIgnoreCase("clip")) {
            if (args.length != 3) {
                sender.sendMessage(USAGE);
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
                    clipManager.saveClip(target); // fire-and-forget; async "Saved clip: <path>" log is confirmation
                }
            } else {
                sender.sendMessage(USAGE);
            }

        } else if (sub.equalsIgnoreCase("verify")) {
            if (args.length != 2) {
                sender.sendMessage(USAGE);
                return true;
            }
            String filename = args[1];
            Path target = resolveReplayFile(filename);
            if (target == null) {
                sender.sendMessage("Unknown replay file: " + filename);
                return true;
            }

            ReplayDecodeVerifier.Result result =
                    ReplayDecodeVerifier.verify(target, registryAccess.get());

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
            sender.sendMessage(USAGE);
        }

        return true;
    }

    /** Resolves {@code filename} under replaysDir first, then clipsDir. Returns {@code null} if not found. */
    private Path resolveReplayFile(String filename) {
        Path inReplays = replaysDir.resolve(filename);
        if (java.nio.file.Files.exists(inReplays)) return inReplays;
        Path inClips = clipsDir.resolve(filename);
        if (java.nio.file.Files.exists(inClips)) return inClips;
        return null;
    }
}
