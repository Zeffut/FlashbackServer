package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.ReplayAction;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClipBufferTest {

    // Sentinel keyframe actions for identity assertions
    private static final ReplayAction k0 = new ReplayAction("flashback:action/create_local_player", new byte[]{0});
    private static final ReplayAction k1 = new ReplayAction("flashback:action/create_local_player", new byte[]{1});
    private static final ReplayAction k2 = new ReplayAction("flashback:action/create_local_player", new byte[]{2});

    private long countNextTicks(List<ReplayAction> actions) {
        return actions.stream().filter(x -> x.identifier().equals("flashback:action/next_tick")).count();
    }

    /** 1. Fresh buffer needs a keyframe; after setKeyframe it doesn't. */
    @Test
    void needsKeyframeWhenEmptyThenSeeded() {
        ClipBuffer buf = ClipBuffer.ofTicks(2);
        assertTrue(buf.needsKeyframe(), "fresh buffer must need a keyframe");
        buf.setKeyframe(List.of(k0));
        assertFalse(buf.needsKeyframe(), "after setKeyframe it must not need a new one");
    }

    /** 2. After windowTicks completed ticks, needsKeyframe becomes true again. */
    @Test
    void rotatesAfterWindow() {
        ClipBuffer buf = ClipBuffer.ofTicks(2);
        buf.setKeyframe(List.of(k0));
        buf.onTick();
        buf.onTick();
        assertTrue(buf.needsKeyframe(), "after window ticks, buffer must request a new keyframe");
    }

    /** 3. clipSnapshotActions returns oldest keyframe; clipTickCount sums completed frames. */
    @Test
    void clipSnapshotIsOldestKeyframe() {
        ClipBuffer buf = ClipBuffer.ofTicks(2);
        buf.setKeyframe(List.of(k0));
        // two completed ticks in seg0
        buf.onPacket(new byte[]{10});
        buf.onTick();
        buf.onPacket(new byte[]{11});
        buf.onTick();
        // rotate: now needsKeyframe == true, start seg1
        buf.setKeyframe(List.of(k1));
        buf.onPacket(new byte[]{20});
        buf.onTick();

        List<ReplayAction> snapshot = buf.clipSnapshotActions();
        assertEquals(1, snapshot.size(), "snapshot should be the k0 list");
        assertSame(k0, snapshot.get(0), "snapshot action must be k0 (oldest segment)");

        // 2 ticks from seg0 + 1 tick from seg1 = 3
        assertEquals(3, buf.clipTickCount());
    }

    /** 4. Third segment causes the oldest to be dropped; snapshot is k1. */
    @Test
    void dropsThirdSegment() {
        ClipBuffer buf = ClipBuffer.ofTicks(2);
        buf.setKeyframe(List.of(k0));
        buf.onTick();
        buf.onTick();
        buf.setKeyframe(List.of(k1));
        buf.onTick();
        buf.onTick();
        buf.setKeyframe(List.of(k2));

        List<ReplayAction> snapshot = buf.clipSnapshotActions();
        assertEquals(1, snapshot.size());
        assertSame(k1, snapshot.get(0), "after 3 segments, k0 must be dropped, snapshot is k1");
    }

    /** 5. clipStreamActions emits game_packet then next_tick for each completed tick; clipTickCount==1. */
    @Test
    void clipStreamHasGamePacketsThenNextTick() {
        ClipBuffer buf = ClipBuffer.ofTicks(2);
        buf.setKeyframe(List.of(k0));
        buf.onPacket(new byte[]{9});
        buf.onTick();

        List<ReplayAction> stream = buf.clipStreamActions();
        assertEquals(2, stream.size(), "should have one game_packet + one next_tick");
        assertEquals("flashback:action/game_packet", stream.get(0).identifier());
        assertArrayEquals(new byte[]{9}, stream.get(0).payload());
        assertEquals("flashback:action/next_tick", stream.get(1).identifier());

        assertEquals(1, buf.clipTickCount());
    }

    /** 6. Packets and ticks before the first keyframe are silently ignored. */
    @Test
    void ignoresPacketsBeforeFirstKeyframe() {
        ClipBuffer buf = ClipBuffer.ofTicks(2);
        buf.onPacket(new byte[]{1});
        buf.onTick();

        assertTrue(buf.clipStreamActions().isEmpty(), "stream should be empty before any keyframe");
        assertEquals(0, buf.clipTickCount());
    }
}
