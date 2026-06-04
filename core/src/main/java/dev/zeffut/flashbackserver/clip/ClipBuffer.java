package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.ReplayAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two-segment keyframe rolling clip buffer.
 *
 * <p>Keeps at most two {@link Segment}s. Each segment starts with a dynamic keyframe
 * (the world-state snapshot at the beginning of that window) and accumulates completed
 * per-tick frames. After {@code windowTicks} ticks have been committed to the current
 * segment, {@link #needsKeyframe()} returns {@code true} so the caller can supply a fresh
 * keyframe via {@link #setKeyframe}, starting a new segment (oldest dropped when a third
 * would be added). This guarantees at least one full window of frames is always accessible
 * with an exact starting keyframe.
 *
 * <p>Thread-safe: a single {@link ReentrantLock} guards all mutable state.
 */
public final class ClipBuffer {
    static final String GAME_PACKET_ACTION = "flashback:action/game_packet";
    static final String NEXT_TICK_ACTION   = "flashback:action/next_tick";

    /** Immutable description of one rolling window: keyframe + completed tick frames. */
    private record Segment(List<ReplayAction> keyframe, Deque<List<byte[]>> frames) {}

    private final int windowTicks;

    /** At most 2 segments, oldest first. */
    private final Deque<Segment> segments = new ArrayDeque<>(2);

    /** Packets accumulated since the last onTick() (in-progress frame). */
    private List<byte[]> current = new ArrayList<>();

    /** Ticks committed to the current (newest) segment since its keyframe was set. */
    private int ticksInCurrentSegment = 0;

    private final ReentrantLock lock = new ReentrantLock();

    // ── Constructors ──────────────────────────────────────────────────────────

    /** @param windowSeconds clip length in seconds (×20 ticks/second). */
    public ClipBuffer(int windowSeconds) {
        this.windowTicks = Math.max(1, windowSeconds) * 20;
    }

    /** Package-private: construct with an explicit tick window (tests). */
    static ClipBuffer ofTicks(int windowTicks) {
        return new ClipBuffer(windowTicks, false);
    }

    /** Private constructor used by {@link #ofTicks}. The boolean param just disambiguates. */
    private ClipBuffer(int windowTicks, @SuppressWarnings("unused") boolean raw) {
        this.windowTicks = Math.max(1, windowTicks);
    }

    // ── Mutation (Netty / region thread) ─────────────────────────────────────

    /**
     * Appends a raw packet to the in-progress frame.
     * Silently ignored if no keyframe has been set yet (clips require a keyframe to render).
     */
    public void onPacket(byte[] idAndPayload) {
        lock.lock();
        try {
            if (segments.isEmpty()) return; // no keyframe yet — ignore
            current.add(idAndPayload);
        } finally { lock.unlock(); }
    }

    /**
     * Finalises the in-progress frame and appends it to the current segment.
     * No-op if no keyframe has been set yet.
     */
    public void onTick() {
        lock.lock();
        try {
            if (segments.isEmpty()) return; // no keyframe yet — ignore
            segments.peekLast().frames().addLast(current);
            current = new ArrayList<>();
            ticksInCurrentSegment++;
        } finally { lock.unlock(); }
    }

    // ── Keyframe management (region thread) ──────────────────────────────────

    /**
     * Returns {@code true} when a new keyframe is required:
     * either no segment exists yet, or the current segment has accumulated
     * {@code windowTicks} completed frames.
     */
    public boolean needsKeyframe() {
        lock.lock();
        try {
            return segments.isEmpty() || ticksInCurrentSegment >= windowTicks;
        } finally { lock.unlock(); }
    }

    /**
     * Starts a new segment with the provided dynamic keyframe.
     * Keeps at most 2 segments — the oldest is dropped when a third would be added.
     * Resets {@code ticksInCurrentSegment} to 0.
     *
     * @param dynamicKeyframe snapshot of dynamic world state at the start of this window
     */
    public void setKeyframe(List<ReplayAction> dynamicKeyframe) {
        lock.lock();
        try {
            if (segments.size() >= 2) segments.removeFirst(); // keep ≤ 2
            segments.addLast(new Segment(List.copyOf(dynamicKeyframe), new ArrayDeque<>()));
            current = new ArrayList<>();
            ticksInCurrentSegment = 0;
        } finally { lock.unlock(); }
    }

    // ── Clip read-out (command thread) ───────────────────────────────────────

    /**
     * Returns the dynamic keyframe of the <em>oldest</em> retained segment.
     * This is the starting world-state for the clip.
     * Returns an empty list if no segment has been seeded yet.
     */
    public List<ReplayAction> clipSnapshotActions() {
        lock.lock();
        try {
            if (segments.isEmpty()) return List.of();
            return segments.peekFirst().keyframe();
        } finally { lock.unlock(); }
    }

    /**
     * Builds the ordered stream of replay actions covering all retained segments.
     *
     * <p>For each segment (oldest → newest), for each completed frame:
     * emits each packet as a {@code game_packet} action then a {@code next_tick} action.
     * Finally appends any in-progress {@code current} packets as {@code game_packet}s
     * (no trailing {@code next_tick}).
     */
    public List<ReplayAction> clipStreamActions() {
        lock.lock();
        try {
            List<ReplayAction> actions = new ArrayList<>();
            for (Segment seg : segments) {
                for (List<byte[]> frame : seg.frames()) {
                    for (byte[] p : frame) actions.add(new ReplayAction(GAME_PACKET_ACTION, p));
                    actions.add(new ReplayAction(NEXT_TICK_ACTION, new byte[0]));
                }
            }
            for (byte[] p : current) actions.add(new ReplayAction(GAME_PACKET_ACTION, p));
            return actions;
        } finally { lock.unlock(); }
    }

    /**
     * Returns the number of {@code next_tick} actions that would appear in
     * {@link #clipStreamActions()} — i.e. the total completed frames across all retained segments.
     */
    public int clipTickCount() {
        lock.lock();
        try {
            int count = 0;
            for (Segment seg : segments) count += seg.frames().size();
            return count;
        } finally { lock.unlock(); }
    }

    /** An atomically-captured clip: snapshot keyframe, stream actions, and tick count, all consistent. */
    public record ClipData(List<ReplayAction> snapshot, List<ReplayAction> stream, int tickCount) {}

    /**
     * Captures the snapshot keyframe, the stream actions, and the tick count under a SINGLE lock
     * acquisition, so a concurrent {@link #setKeyframe} rotation cannot tear them apart (which would
     * misalign the snapshot with the first emitted frame or the declared tick count). Callers writing
     * a clip file should use this rather than the three separate accessors.
     */
    public ClipData captureClip() {
        lock.lock();
        try {
            List<ReplayAction> snapshot = segments.isEmpty() ? List.of() : segments.peekFirst().keyframe();
            List<ReplayAction> stream = new ArrayList<>();
            int ticks = 0;
            for (Segment seg : segments) {
                for (List<byte[]> frame : seg.frames()) {
                    for (byte[] p : frame) stream.add(new ReplayAction(GAME_PACKET_ACTION, p));
                    stream.add(new ReplayAction(NEXT_TICK_ACTION, new byte[0]));
                    ticks++;
                }
            }
            for (byte[] p : current) stream.add(new ReplayAction(GAME_PACKET_ACTION, p));
            return new ClipData(snapshot, stream, ticks);
        } finally { lock.unlock(); }
    }
}
