package dev.zeffut.flashbackserver.command;

import dev.zeffut.flashbackserver.clip.ClipManager;
import dev.zeffut.flashbackserver.record.RecordingManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class ReplayCommand implements CommandExecutor {
    private final RecordingManager manager;
    private final ClipManager clipManager;

    public ReplayCommand(RecordingManager manager, ClipManager clipManager) {
        this.manager = manager;
        this.clipManager = clipManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /replay <start|stop> players <player> | /replay clip <arm|disarm|save> <player>");
            return true;
        }

        String sub = args[0];

        if (sub.equalsIgnoreCase("start") || sub.equalsIgnoreCase("stop")) {
            if (args.length != 3 || !args[1].equalsIgnoreCase("players")) {
                sender.sendMessage("Usage: /replay <start|stop> players <player> | /replay clip <arm|disarm|save> <player>");
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
                sender.sendMessage("Usage: /replay <start|stop> players <player> | /replay clip <arm|disarm|save> <player>");
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
                sender.sendMessage("Usage: /replay <start|stop> players <player> | /replay clip <arm|disarm|save> <player>");
            }

        } else {
            sender.sendMessage("Usage: /replay <start|stop> players <player> | /replay clip <arm|disarm|save> <player>");
        }

        return true;
    }
}
