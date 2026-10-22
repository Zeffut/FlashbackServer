package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.*;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class ReplayFiles {
    private ReplayFiles() {}

    /**
     * Resolves a caller-supplied output path for the public save APIs.
     *
     * <p>Absolute paths are used as-is; relative paths are resolved against the plugin data
     * folder ({@code plugin.getDataFolder()}) and normalized. Callers handle {@code null}
     * (default location) before calling this.
     */
    public static Path resolveOutput(Plugin plugin, Path requested) {
        Path p = requested.isAbsolute()
                ? requested
                : plugin.getDataFolder().toPath().resolve(requested);
        return p.normalize();
    }

    /** Atomically reserves a previously unused default output filename. */
    public static Path reserveUnique(Path directory, String stem, String extension) throws java.io.IOException {
        return reserveUnique(directory, stem, extension, 0);
    }

    /** Atomically reserves a previously unused default output filename with a numbered first candidate. */
    public static Path reserveUnique(Path directory, String stem, String extension, int firstNumber)
            throws java.io.IOException {
        Files.createDirectories(directory);
        for (long number = firstNumber; ; number++) {
            String suffix = number == 0 ? "" : "-" + number;
            Path candidate = directory.resolve(stem + suffix + extension);
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException collision) {
                // An existing replay may belong to a previous process: never replace it.
            }
        }
    }

    /** Compares paths by normalized location and, when possible, filesystem identity. */
    public static boolean sameFile(Path left, Path right) {
        Path normalizedLeft = left.toAbsolutePath().normalize();
        Path normalizedRight = right.toAbsolutePath().normalize();
        if (normalizedLeft.equals(normalizedRight)) return true;
        try {
            return Files.exists(normalizedLeft) && Files.exists(normalizedRight)
                    && Files.isSameFile(normalizedLeft, normalizedRight);
        } catch (java.io.IOException ignored) {
            return false;
        }
    }

    /**
     * The Minecraft version written into the container's metadata.
     *
     * <p>Read from the running server rather than hardcoded: the Flashback client compares this
     * field against its own version and refuses to open a mismatched recording, so a stale
     * constant makes every recording unopenable on any version but that one. This is the same
     * source {@link dev.zeffut.flashbackserver.version.VersionAdapters} already uses to select
     * the adapter, so the two can no longer disagree.
     *
     * <p>Falls back to the previous hardcoded value if Bukkit is not available (unit tests).
     */
    private static String minecraftVersion() {
        try {
            String v = Bukkit.getMinecraftVersion();
            if (v != null && !v.isBlank()) return v;
        } catch (Throwable ignored) {
            // No running server (tests) -- fall through.
        }
        return "1.21.5";
    }

    /** Represents a single chunk to be written. */
    public record Chunk(List<ReplayAction> snapshot, List<ReplayAction> stream, int tickCount, boolean forcePlaySnapshot) {}

    /**
     * Writes a multi-chunk .flashback file from the given list of chunks.
     * Chunk 0 typically carries the snapshot and forcePlaySnapshot=true.
     */
    public static void write(Path output, String playerName, int protocolVersion, int dataVersion,
                             List<Chunk> chunks) throws Exception {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FlashbackMeta meta = new FlashbackMeta();
        meta.name = playerName;
        meta.versionString = minecraftVersion();
        meta.protocolVersion = protocolVersion;
        meta.dataVersion = dataVersion;
        meta.totalTicks = chunks.stream().mapToInt(Chunk::tickCount).sum();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            meta.chunks.put("c" + i + ".flashback", new ChunkMeta(c.tickCount(), c.forcePlaySnapshot()));
        }
        try (var writer = FlashbackContainer.create(output)) {
            writer.writeMetadata(meta);
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                writer.writeChunk("c" + i + ".flashback", ChunkWriter.write(c.snapshot(), c.stream()));
            }
        }
    }

    /** Writes a single-chunk .flashback from the given snapshot and stream actions. tickCount = number of next_tick actions. */
    public static void write(Path output, String playerName, int protocolVersion, int dataVersion,
                             List<ReplayAction> snapshotActions, List<ReplayAction> streamActions, int tickCount) throws Exception {
        write(output, playerName, protocolVersion, dataVersion,
              List.of(new Chunk(snapshotActions, streamActions, tickCount, true)));
    }
}
