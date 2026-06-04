package dev.zeffut.flashbackserver.record;

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
    private final int chunkLengthTicks;

    private final ReentrantLock lock = new ReentrantLock();

    /** Completed (rolled-over) chunks, each a stream action list. */
    private final List<List<ReplayAction>> completedChunks = new ArrayList<>();
    /** The in-progress chunk's stream actions. */
    private List<ReplayAction> currentChunk = new ArrayList<>();
    /** Ticks accumulated in the current (not yet completed) chunk. */
    private int ticksInChunk = 0;
    /** Total ticks across all chunks. */
    private int totalTicks = 0;

    private boolean stopped = false;

    /** Initial-state snapshot actions; set once before stop() via setSnapshot(). */
    private List<ReplayAction> snapshot = List.of();

    public FlashbackRecorder(Path output, String playerName, int protocolVersion, int dataVersion) {
        this(output, playerName, protocolVersion, dataVersion, 6000);
    }

    private FlashbackRecorder(Path output, String playerName, int protocolVersion, int dataVersion, int chunkLengthTicks) {
        this.output           = output;
        this.playerName       = playerName;
        this.protocolVersion  = protocolVersion;
        this.dataVersion      = dataVersion;
        this.chunkLengthTicks = chunkLengthTicks;
    }

    /** Factory that creates a recorder with a custom chunk-length threshold (for testing). */
    public static FlashbackRecorder withChunkLength(Path output, String playerName, int protocolVersion, int dataVersion, int chunkLengthTicks) {
        return new FlashbackRecorder(output, playerName, protocolVersion, dataVersion, chunkLengthTicks);
    }

    /**
     * Sets the initial-state snapshot actions. Must be called before stop().
     * Safe to call from the region/main thread while recording is in progress.
     */
    public void setSnapshot(List<ReplayAction> snapshot) {
        lock.lock();
        try {
            this.snapshot = List.copyOf(snapshot);
        } finally {
            lock.unlock();
        }
    }

    /** May be called from the Netty I/O thread. */
    public void onPacket(byte[] idAndPayload) {
        lock.lock();
        try {
            if (stopped) return;
            currentChunk.add(new ReplayAction(GAME_PACKET_ACTION, idAndPayload));
        } finally {
            lock.unlock();
        }
    }

    /** Must be called from the server main thread (one tick per call). */
    public void onTick() {
        lock.lock();
        try {
            if (stopped) return;
            currentChunk.add(new ReplayAction(NEXT_TICK_ACTION, new byte[0]));
            ticksInChunk++;
            totalTicks++;
            if (ticksInChunk >= chunkLengthTicks) {
                rollChunkInternal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Manually forces a chunk boundary. Public; safe to call externally (e.g. for testing).
     * No-op if the current chunk is empty.
     */
    public void rollChunk() {
        lock.lock();
        try {
            if (stopped) return;
            rollChunkInternal();
        } finally {
            lock.unlock();
        }
    }

    /** Internal roll — must be called with lock held. */
    private void rollChunkInternal() {
        if (!currentChunk.isEmpty()) {
            completedChunks.add(currentChunk);
            currentChunk = new ArrayList<>();
            ticksInChunk = 0;
        }
    }

    /** Idempotent: second and subsequent calls are no-ops. */
    public void stop() throws Exception {
        List<ReplayAction> snapshotCopy;
        List<List<ReplayAction>> allChunks;

        lock.lock();
        try {
            if (stopped) return;
            stopped = true;
            snapshotCopy = new ArrayList<>(snapshot);
            // Roll the final in-progress chunk if non-empty.
            rollChunkInternal();
            allChunks = new ArrayList<>(completedChunks);
        } finally {
            lock.unlock();
        }

        List<ReplayFiles.Chunk> chunks = new ArrayList<>();

        if (allChunks.isEmpty()) {
            // No ticks at all — write a single empty chunk with the snapshot.
            chunks.add(new ReplayFiles.Chunk(snapshotCopy, List.of(), 0, true));
        } else {
            for (int i = 0; i < allChunks.size(); i++) {
                List<ReplayAction> stream = allChunks.get(i);
                int ticks = countNextTicks(stream);
                if (i == 0) {
                    chunks.add(new ReplayFiles.Chunk(snapshotCopy, stream, ticks, true));
                } else {
                    chunks.add(new ReplayFiles.Chunk(List.of(), stream, ticks, false));
                }
            }
        }

        ReplayFiles.write(output, playerName, protocolVersion, dataVersion, chunks);
    }

    private static int countNextTicks(List<ReplayAction> actions) {
        int count = 0;
        for (ReplayAction a : actions) {
            if (NEXT_TICK_ACTION.equals(a.identifier())) count++;
        }
        return count;
    }
}
