package dev.zeffut.flashbackserver.command;

import dev.zeffut.flashbackserver.record.RecordingManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class ReplayCommand implements CommandExecutor {
    private final RecordingManager manager;
    public ReplayCommand(RecordingManager manager) { this.manager = manager; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 3 || !args[1].equalsIgnoreCase("players")) {
            sender.sendMessage("Usage: /replay <start|stop> players <player>");
            return true;
        }
        Player target = sender.getServer().getPlayerExact(args[2]);
        if (target == null) { sender.sendMessage("Unknown player: " + args[2]); return true; }
        if (args[0].equalsIgnoreCase("start")) {
            try {
                sender.sendMessage(manager.start(target) ? "Recording " + target.getName()
                    : target.getName() + " is already being recorded");
            } catch (Exception e) {
                sender.sendMessage("Replay error: " + e.getMessage());
            }
        } else if (args[0].equalsIgnoreCase("stop")) {
            if (!manager.isRecording(target)) {
                sender.sendMessage(target.getName() + " was not being recorded");
            } else {
                sender.sendMessage("Stopping recording for " + target.getName() + " (writing replay…)");
                manager.stop(target); // async; logs "Saved replay: <path>" when the file is on disk
            }
        } else {
            sender.sendMessage("Usage: /replay <start|stop> players <player>");
        }
        return true;
    }
}
