package dev.zeffut.flashbackserver.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ReplayTabCompleter implements TabCompleter {

    private static final List<String> TOP_LEVEL = List.of("start", "stop", "clip", "verify", "help");
    private static final List<String> CLIP_ACTIONS = List.of("arm", "disarm", "save");
    private static final Set<String> PLAYER_SUBS = Set.of("start", "stop", "clip");

    private final Path replaysDir;
    private final Path clipsDir;

    public ReplayTabCompleter(Path replaysDir, Path clipsDir) {
        this.replaysDir = replaysDir;
        this.clipsDir = clipsDir;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return TOP_LEVEL.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String prefix = args[1].toLowerCase();
            if (sub.equals("start") || sub.equals("stop")) {
                return List.of("players").stream()
                        .filter(s -> s.startsWith(prefix))
                        .collect(Collectors.toList());
            } else if (sub.equals("clip")) {
                return CLIP_ACTIONS.stream()
                        .filter(s -> s.startsWith(prefix))
                        .collect(Collectors.toList());
            } else if (sub.equals("verify")) {
                return listFlashbackFiles(prefix);
            }
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (PLAYER_SUBS.contains(sub)) {
                String prefix = args[2].toLowerCase();
                return sender.getServer().getOnlinePlayers().stream()
                        .map(p -> p.getName())
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    private List<String> listFlashbackFiles(String prefix) {
        List<String> results = new ArrayList<>();
        for (Path dir : List.of(replaysDir, clipsDir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.map(p -> p.getFileName().toString())
                        .filter(name -> name.endsWith(".flashback") && name.toLowerCase().startsWith(prefix.toLowerCase()))
                        .forEach(results::add);
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return results;
    }
}
