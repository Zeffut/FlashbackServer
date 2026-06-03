package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.ChunkMeta;
import dev.zeffut.flashbackserver.format.ChunkWriter;
import dev.zeffut.flashbackserver.format.FlashbackContainer;
import dev.zeffut.flashbackserver.format.FlashbackMeta;
import dev.zeffut.flashbackserver.format.ReplayAction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public final class FlashbackRecorder {

    public static final String GAME_PACKET_ACTION = "flashback:action/game_packet";
    public static final String NEXT_TICK_ACTION   = "flashback:action/next_tick";

    private final Path output;
    private final String playerName;
    private final int protocolVersion;
    private final int dataVersion;

    private final ReentrantLock lock = new ReentrantLock();
    private final List<ReplayAction> actions = new ArrayList<>();
    private int tickCount = 0;
    private boolean stopped = false;

    public FlashbackRecorder(Path output, String playerName, int protocolVersion, int dataVersion) {
        this.output          = output;
        this.playerName      = playerName;
        this.protocolVersion = protocolVersion;
        this.dataVersion     = dataVersion;
    }

    /** May be called from the Netty I/O thread. */
    public void onPacket(byte[] idAndPayload) {
        lock.lock();
        try {
            if (stopped) return;
            actions.add(new ReplayAction(GAME_PACKET_ACTION, idAndPayload));
        } finally {
            lock.unlock();
        }
    }

    /** Must be called from the server main thread (one tick per call). */
    public void onTick() {
        lock.lock();
        try {
            if (stopped) return;
            actions.add(new ReplayAction(NEXT_TICK_ACTION, new byte[0]));
            tickCount++;
        } finally {
            lock.unlock();
        }
    }

    /** Idempotent: second and subsequent calls are no-ops. */
    public void stop() throws Exception {
        List<ReplayAction> snapshot;
        int ticks;

        lock.lock();
        try {
            if (stopped) return;
            stopped = true;
            snapshot = new ArrayList<>(actions);
            ticks    = tickCount;
        } finally {
            lock.unlock();
        }

        byte[] chunkBytes = ChunkWriter.write(new byte[0], snapshot);

        FlashbackMeta meta = new FlashbackMeta();
        meta.name            = playerName;
        meta.versionString   = "1.21.5";
        meta.protocolVersion = protocolVersion;
        meta.dataVersion     = dataVersion;
        meta.totalTicks      = ticks;
        meta.chunks.put("c0.flashback", new ChunkMeta(ticks));

        try (FlashbackContainer.Writer writer = FlashbackContainer.create(output)) {
            writer.writeMetadata(meta);
            writer.writeChunk("c0.flashback", chunkBytes);
        }
    }
}
