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

    private final ReentrantLock lock = new ReentrantLock();
    private final List<ReplayAction> actions = new ArrayList<>();
    private int tickCount = 0;
    private boolean stopped = false;

    /** Initial-state snapshot actions; set once before stop() via setSnapshot(). */
    private List<ReplayAction> snapshot = List.of();

    public FlashbackRecorder(Path output, String playerName, int protocolVersion, int dataVersion) {
        this.output          = output;
        this.playerName      = playerName;
        this.protocolVersion = protocolVersion;
        this.dataVersion     = dataVersion;
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
        List<ReplayAction> snapshotCopy;
        List<ReplayAction> streamCopy;
        int ticks;

        lock.lock();
        try {
            if (stopped) return;
            stopped = true;
            snapshotCopy = new ArrayList<>(snapshot);
            streamCopy   = new ArrayList<>(actions);
            ticks        = tickCount;
        } finally {
            lock.unlock();
        }

        ReplayFiles.write(output, playerName, protocolVersion, dataVersion, snapshotCopy, streamCopy, ticks);
    }
}
