package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.*;
import java.nio.file.Path;
import java.util.List;

public final class ReplayFiles {
    private ReplayFiles() {}

    /** Writes a single-chunk .flashback from the given snapshot and stream actions. tickCount = number of next_tick actions. */
    public static void write(Path output, String playerName, int protocolVersion, int dataVersion,
                             List<ReplayAction> snapshotActions, List<ReplayAction> streamActions, int tickCount) throws Exception {
        byte[] chunk = ChunkWriter.write(snapshotActions, streamActions);
        FlashbackMeta meta = new FlashbackMeta();
        meta.name = playerName;
        meta.versionString = "1.21.5";
        meta.protocolVersion = protocolVersion;
        meta.dataVersion = dataVersion;
        meta.totalTicks = tickCount;
        meta.chunks.put("c0.flashback", new ChunkMeta(tickCount));
        try (var writer = FlashbackContainer.create(output)) {
            writer.writeMetadata(meta);
            writer.writeChunk("c0.flashback", chunk);
        }
    }
}
