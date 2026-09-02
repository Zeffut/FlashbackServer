package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.format.VarCodec;
import dev.zeffut.flashbackserver.version.VersionAdapter.EntityPosition;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns per-tick entity positions into {@code flashback:action/move_entities} payloads.
 *
 * <h3>Why this exists</h3>
 * Minecraft conveys movement with {@code ClientboundMoveEntityPacket} — small deltas relative to
 * the last known position. Flashback refuses that packet outright (see
 * {@link dev.zeffut.flashbackserver.capture.RefusedPackets}) and reads movement <em>only</em> from
 * its own {@code move_entities} action, which carries absolute coordinates. A recorder that
 * forwards the vanilla stream unchanged therefore records a world in which nothing ever moves, and
 * one that forwards it <em>including</em> the refused packets records a file that will not open.
 * Re-deriving absolute positions once per tick is the only shape the format accepts.
 *
 * <h3>Payload layout</h3>
 * Matching {@code ReplayServer#handleMoveEntities} — one dimension per payload, since a recording
 * follows a single player:
 * <pre>
 *   varint  levelCount            (always 1)
 *   string  dimension             (varint length + UTF-8, e.g. "minecraft:overworld")
 *   varint  entityCount
 *   per entity:
 *     varint  entityId
 *     double  x, y, z             (big-endian, 8 bytes each)
 *     float   yaw, pitch, headYaw (big-endian, 4 bytes each)
 *     byte    onGround            (0 or 1)
 * </pre>
 *
 * <h3>Only what changed</h3>
 * Entities whose position is identical to the previous tick are omitted, as Flashback's own
 * recorder does. On a mostly-idle world that is the difference between a payload per tick and
 * nothing at all — {@link #payload} returns null when nothing moved and the caller writes no
 * action. Positions are compared exactly, not within a tolerance: the values come from the server's
 * own doubles, so an entity that has not moved reports bit-identical coordinates.
 *
 * <p>Not thread-safe. One instance per recording, used from the player's region thread.
 */
public final class EntityPositionTracker {

    public static final String MOVE_ENTITIES_ACTION = "flashback:action/move_entities";

    private Map<Integer, EntityPosition> last = new HashMap<>();

    /**
     * Builds the payload for everything that moved since the previous call, and adopts
     * {@code current} as the new baseline.
     *
     * @param dimensionKey the player's dimension, e.g. {@code "minecraft:overworld"}
     * @param current every entity the client can see this tick, the player included
     * @return the payload bytes, or null if no entity's position changed
     */
    public byte[] payload(String dimensionKey, List<EntityPosition> current) {
        List<EntityPosition> changed = new ArrayList<>();
        // Rebuilt rather than updated in place: entities that despawned must leave the baseline,
        // or a recycled entity id would be diffed against a position that belonged to something
        // else and its first real move would be suppressed.
        Map<Integer, EntityPosition> next = new HashMap<>(current.size() * 2);
        for (EntityPosition pos : current) {
            next.put(pos.entityId(), pos);
            if (!pos.equals(last.get(pos.entityId()))) changed.add(pos);
        }
        last = next;
        if (changed.isEmpty()) return null;
        return encode(dimensionKey, changed);
    }

    /** Drops the baseline, so the next call reports every entity as changed. */
    public void reset() {
        last = new HashMap<>();
    }

    /** Encodes a payload for the given positions. Exposed for tests. */
    public static byte[] encode(String dimensionKey, List<EntityPosition> positions) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            VarCodec.writeVarInt(out, 1); // one dimension
            VarCodec.writeString(out, dimensionKey);
            VarCodec.writeVarInt(out, positions.size());
            for (EntityPosition p : positions) {
                VarCodec.writeVarInt(out, p.entityId());
                out.writeDouble(p.x());
                out.writeDouble(p.y());
                out.writeDouble(p.z());
                out.writeFloat(p.yaw());
                out.writeFloat(p.pitch());
                out.writeFloat(p.headYaw());
                out.writeBoolean(p.onGround());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Encoding move_entities to a byte array cannot fail", e);
        }
        return bytes.toByteArray();
    }
}
