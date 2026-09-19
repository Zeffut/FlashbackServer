package dev.zeffut.flashbackserver.record;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Single shared path-resolution used by {@code stop(Player, Path)} and {@code saveClip(Player, Path)}. */
class ReplayFilesResolveOutputTest {

    @TempDir
    Path dataFolder;

    @Test
    void absoluteUsedAsIs() {
        Path abs = dataFolder.resolve("custom/here.flashback").toAbsolutePath().normalize();
        assertEquals(abs, ReplayFiles.resolveOutput(plugin(), abs));
    }

    @Test
    void relativeResolvedAgainstDataFolder() {
        Path resolved = ReplayFiles.resolveOutput(plugin(), Path.of("clips/named.flashback"));
        assertEquals(dataFolder.toAbsolutePath().normalize().resolve("clips/named.flashback"), resolved);
    }

    private Plugin plugin() {
        File dir = dataFolder.toFile();
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "FlashbackServer";
                    case "getDataFolder" -> dir;
                    case "toString" -> "StubPlugin";
                    case "hashCode" -> dataFolder.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
