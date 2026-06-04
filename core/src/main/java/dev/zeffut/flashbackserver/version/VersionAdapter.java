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

    /**
     * Decodes {@code snapshotActions} and {@code streamActions} through the real Minecraft codecs
     * and returns a summary of successes and failures.  Used by {@code /replay verify}.
     */
    DecodeResult decode(List<ReplayAction> snapshotActions, List<ReplayAction> streamActions);

    /** Summary of a decode pass over one chunk's actions. */
    record DecodeResult(int decoded, int errors, List<String> problems) {}
}
