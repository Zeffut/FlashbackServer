package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.*;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Bukkit;

public final class ReplayFiles {
    private ReplayFiles() {}

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
