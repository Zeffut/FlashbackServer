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
        try {
            if (args[0].equalsIgnoreCase("start")) {
                sender.sendMessage(manager.start(target) ? "Recording " + target.getName()
                    : target.getName() + " is already being recorded");
            } else if (args[0].equalsIgnoreCase("stop")) {
                var path = manager.stop(target);
                sender.sendMessage(path != null ? "Saved replay: " + path
                    : target.getName() + " was not being recorded");
            } else {
                sender.sendMessage("Usage: /replay <start|stop> players <player>");
            }
        } catch (Exception e) {
            sender.sendMessage("Replay error: " + e.getMessage());
        }
        return true;
    }
}
