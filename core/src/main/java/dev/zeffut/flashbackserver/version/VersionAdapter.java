package dev.zeffut.flashbackserver.version;

import dev.zeffut.flashbackserver.format.ReplayAction;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Version-specific NMS abstraction.  The interface carries only Netty, Bukkit, and our own format
 * types — {@code net.minecraft} and {@code org.bukkit.craftbukkit} are strictly confined to the
 * concrete implementation(s) in {@code version/v1_21_5/}.
 *
 * <h3>Snapshot ordering contract</h3>
 * Core's {@code SnapshotBuilder} assembles the final snapshot as:
 * <pre>
 *   configActions(player)
 *   ++ loginAction(player)
 *   ++ [CreateLocalPlayerAction.payload(player)]   ← inserted by core, not the adapter
 *   ++ postLoginActions(player)
 * </pre>
 * which produces the canonical order:
 * <ol>
 *   <li>configuration_packet × N  (features + registry + tags)</li>
 *   <li>game_packet — login</li>
 *   <li>create_local_player</li>
 *   <li>game_packet — position</li>
 *   <li>game_packet — player-info</li>
 *   <li>game_packet — chunks × N</li>
 * </ol>
 *
 * <p><strong>Threading:</strong> {@link #configActions}, {@link #loginAction}, and
 * {@link #postLoginActions} MUST be called on the player's region thread (Folia) or the main
 * thread (Paper).
 */
public interface VersionAdapter {

    /**
     * Returns the Netty {@link Channel} backing this player's connection.
     *
     * @throws IllegalStateException if internals have changed unexpectedly
     */
    Channel channelOf(Player player);

    /** The current Minecraft protocol version (e.g. 770 for 1.21.5). */
    int protocolVersion();

    /** The current Minecraft data version (e.g. 4325 for 1.21.5). */
    int dataVersion();

    /**
     * Configuration-phase actions: {@code ClientboundUpdateEnabledFeaturesPacket},
     * {@code ClientboundSelectKnownPacks}, {@code ClientboundRegistryDataPacket} × N,
     * {@code ClientboundUpdateTagsPacket}.
     *
     * <p>Must be called on the player's region thread.
     */
    List<ReplayAction> configActions(Player player);

    /**
     * A one-element list containing the encoded {@code ClientboundLoginPacket} game_packet action.
     *
     * <p>Must be called on the player's region thread.
     */
    List<ReplayAction> loginAction(Player player);

    /**
     * Post-login PLAY actions (EXCLUDING create_local_player): position, player-info, chunks — in
     * that order.
     *
     * <p>Must be called on the player's region thread.
     */
    List<ReplayAction> postLoginActions(Player player);

    // ─── Live capture ─────────────────────────────────────────────────────────

    /**
     * Binds a {@link PacketTranslator} to this player for the lifetime of one recording.
     *
     * <p>Called once, on the player's Netty event loop, when packet capture is installed. Building
     * the PLAY codec resolves the player's registry access, so this must not happen per packet.
     */
    PacketTranslator translatorFor(Player player);

    /**
     * The player's current dimension as a resource-location string, e.g.
     * {@code "minecraft:overworld"}.
     *
     * <p>Written verbatim into every {@code move_entities} payload, which the client reads back
     * with {@code readResourceKey(Registries.DIMENSION)} and uses to look up the level whose
     * entities are being moved.
     *
     * <p>Must be called on the player's region thread.
     */
    String dimensionKey(Player player);

    /**
     * Absolute positions of every entity the recorded client can see, the player included.
     *
     * <p>The player's own entry is what moves the replay camera, so omitting it would produce a
     * recording that plays back from a fixed point. Order is unspecified; the caller diffs against
     * the previous tick by entity id.
     *
     * <p>Must be called on the player's region thread.
     */
    List<EntityPosition> visibleEntityPositions(Player player);

    /**
     * Decodes {@code snapshotActions} and {@code streamActions} through the real Minecraft codecs
     * and returns a summary of successes and failures.  Used by {@code /replay verify}.
     *
     * <p>Note that decoding is a far weaker check than playback: every one of the 54 packet types
     * in {@link dev.zeffut.flashbackserver.capture.RefusedPackets} decodes perfectly and still
     * crashes the client. A clean {@code /replay verify} means the container is well-formed, not
     * that the replay opens.
     */
    DecodeResult decode(List<ReplayAction> snapshotActions, List<ReplayAction> streamActions);

    /** Summary of a decode pass over one chunk's actions. */
    record DecodeResult(int decoded, int errors, List<String> problems) {}

    /**
     * One entity's absolute position and orientation at a point in time.
     *
     * <p>Mirrors the per-entity record inside a {@code flashback:action/move_entities} payload.
     * Absolute, not relative: the vanilla {@code ClientboundMoveEntityPacket} deltas the client
     * refuses have no equivalent here, which is the whole reason this type exists.
     */
    record EntityPosition(int entityId, double x, double y, double z,
                          float yaw, float pitch, float headYaw, boolean onGround) {}
}
