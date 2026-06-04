package dev.zeffut.flashbackserver.clip;

import dev.zeffut.flashbackserver.format.ReplayAction;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClipBufferTest {
    private long nextTicks(List<ReplayAction> a) {
        return a.stream().filter(x -> x.identifier().equals("flashback:action/next_tick")).count();
    }

    @Test
    void retainsOnlyTheLastWindowOfTicks() {
        ClipBuffer buffer = ClipBuffer.ofTicks(2); // 2-tick window
        for (int i = 0; i < 5; i++) {
            buffer.onPacket(new byte[]{(byte) i});
            buffer.onTick();
        }
        List<ReplayAction> clip = buffer.snapshotClip();
        assertEquals(2, nextTicks(clip), "should retain exactly the window's worth of ticks");
        long gamePackets = clip.stream().filter(x -> x.identifier().equals("flashback:action/game_packet")).count();
        assertEquals(2, gamePackets);
        assertEquals(2, buffer.tickCount());
    }

    @Test
    void emptyBufferYieldsNoActions() {
        ClipBuffer buffer = ClipBuffer.ofTicks(5);
        assertEquals(0, buffer.snapshotClip().size());
        assertEquals(0, buffer.tickCount());
    }

    @Test
    void packetsInTheCurrentUnfinishedTickAreIncluded() {
        ClipBuffer buffer = ClipBuffer.ofTicks(5);
        buffer.onTick();
        buffer.onPacket(new byte[]{9});
        List<ReplayAction> clip = buffer.snapshotClip();
        assertEquals(1, nextTicks(clip));
        assertTrue(clip.stream().anyMatch(x -> x.identifier().equals("flashback:action/game_packet")));
    }

    @Test
    void secondsConstructorUsesTwentyTicksPerSecond() {
        ClipBuffer buffer = new ClipBuffer(1); // 1 second = 20 ticks
        for (int i = 0; i < 25; i++) buffer.onTick();
        assertEquals(20, buffer.tickCount());
    }
}
