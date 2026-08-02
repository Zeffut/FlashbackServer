package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.VarCodec;
import dev.zeffut.flashbackserver.version.VersionAdapter.EntityPosition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The payload assertions here are written against {@code ReplayServer#handleMoveEntities} in
 * Moulberry/Flashback, which is the only reader that will ever parse these bytes. The reader is
 * positional with no length prefixes and no version field, so a field written in the wrong order or
 * the wrong width does not fail — it silently teleports entities to garbage coordinates.
 */
class EntityPositionTrackerTest {

    private static EntityPosition pos(int id, double x) {
        return new EntityPosition(id, x, 64.0, -3.5, 90.0f, -12.0f, 45.0f, true);
    }

    @Test
    void firstTickReportsEverything() {
        var tracker = new EntityPositionTracker();
        assertNotNull(tracker.payload("minecraft:overworld", List.of(pos(1, 0), pos(2, 5))));
    }

    @Test
    void unchangedPositionsProduceNoPayload() {
        var tracker = new EntityPositionTracker();
        tracker.payload("minecraft:overworld", List.of(pos(1, 0)));
        assertNull(tracker.payload("minecraft:overworld", List.of(pos(1, 0))),
                "a tick in which nothing moved must write no action at all");
    }

    @Test
    void onlyChangedEntitiesAreWritten() throws IOException {
        var tracker = new EntityPositionTracker();
        tracker.payload("minecraft:overworld", List.of(pos(1, 0), pos(2, 5)));
        byte[] payload = tracker.payload("minecraft:overworld", List.of(pos(1, 0), pos(2, 9)));

        assertNotNull(payload);
        var in = new DataInputStream(new ByteArrayInputStream(payload));
        assertEquals(1, VarCodec.readVarInt(in), "levelCount");
        assertEquals("minecraft:overworld", VarCodec.readString(in));
        assertEquals(1, VarCodec.readVarInt(in), "only entity 2 moved");
        assertEquals(2, VarCodec.readVarInt(in), "entityId");
        assertEquals(9.0, in.readDouble());
    }

    @Test
    void payloadFieldsAreInTheOrderTheClientReadsThem() throws IOException {
        byte[] payload = EntityPositionTracker.encode("minecraft:the_nether",
                List.of(new EntityPosition(7, 1.5, 2.5, 3.5, 10f, 20f, 30f, false)));

        var in = new DataInputStream(new ByteArrayInputStream(payload));
        assertEquals(1, VarCodec.readVarInt(in), "levelCount");
        assertEquals("minecraft:the_nether", VarCodec.readString(in), "dimension");
        assertEquals(1, VarCodec.readVarInt(in), "entityCount");
        assertEquals(7, VarCodec.readVarInt(in), "entityId");
        assertEquals(1.5, in.readDouble(), "x");
        assertEquals(2.5, in.readDouble(), "y");
        assertEquals(3.5, in.readDouble(), "z");
        assertEquals(10f, in.readFloat(), "yaw");
        assertEquals(20f, in.readFloat(), "pitch");
        assertEquals(30f, in.readFloat(), "headYaw");
        assertFalse(in.readBoolean(), "onGround");
        assertEquals(0, in.available(), "no trailing bytes");
    }

    @Test
    void despawnedEntitiesLeaveTheBaseline() {
        var tracker = new EntityPositionTracker();
        tracker.payload("minecraft:overworld", List.of(pos(1, 0), pos(2, 5)));
        tracker.payload("minecraft:overworld", List.of(pos(1, 0)));           // entity 2 despawns
        // If the baseline still held entity 2's old position, a new entity that reused the id
        // would be diffed against it and its first appearance suppressed.
        assertNotNull(tracker.payload("minecraft:overworld", List.of(pos(1, 0), pos(2, 5))));
    }

    @Test
    void resetMakesEverythingChangedAgain() {
        var tracker = new EntityPositionTracker();
        tracker.payload("minecraft:overworld", List.of(pos(1, 0)));
        tracker.reset();
        assertNotNull(tracker.payload("minecraft:overworld", List.of(pos(1, 0))),
                "after a dimension change the camera's position must be re-sent");
    }
}
