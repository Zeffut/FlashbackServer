package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.ReplayAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Thread-safe bounded ring buffer of recent outbound packets, grouped into per-tick frames. */
public final class ClipBuffer {
    static final String GAME_PACKET_ACTION = "flashback:action/game_packet";
    static final String NEXT_TICK_ACTION = "flashback:action/next_tick";

    private final int windowTicks;
    private final Deque<List<byte[]>> frames = new ArrayDeque<>();
    private List<byte[]> current = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    private ClipBuffer(int windowTicks, boolean ticks) { this.windowTicks = Math.max(1, windowTicks); }

    /** @param windowSeconds clip length in seconds (×20 ticks). */
    public ClipBuffer(int windowSeconds) { this(Math.max(1, windowSeconds) * 20, true); }

    /** Package-private: construct with an explicit tick window (tests). */
    static ClipBuffer ofTicks(int windowTicks) { return new ClipBuffer(windowTicks, true); }

    public void onPacket(byte[] idAndPayload) {
        lock.lock();
        try { current.add(idAndPayload); } finally { lock.unlock(); }
    }

    public void onTick() {
        lock.lock();
        try {
            frames.addLast(current);
            current = new ArrayList<>();
            while (frames.size() > windowTicks) frames.removeFirst();
        } finally { lock.unlock(); }
    }

    public int tickCount() {
        lock.lock();
        try { return frames.size(); } finally { lock.unlock(); }
    }

    public List<ReplayAction> snapshotClip() {
        lock.lock();
        try {
            List<ReplayAction> actions = new ArrayList<>();
            for (List<byte[]> frame : frames) {
                for (byte[] p : frame) actions.add(new ReplayAction(GAME_PACKET_ACTION, p));
                actions.add(new ReplayAction(NEXT_TICK_ACTION, new byte[0]));
            }
            for (byte[] p : current) actions.add(new ReplayAction(GAME_PACKET_ACTION, p));
            return actions;
        } finally { lock.unlock(); }
    }
}
