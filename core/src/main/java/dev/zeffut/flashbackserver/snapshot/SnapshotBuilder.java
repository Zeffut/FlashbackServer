package dev.zeffut.flashbackserver.snapshot;

import dev.zeffut.flashbackserver.format.ReplayAction;
import dev.zeffut.flashbackserver.version.VersionAdapter;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the ordered list of {@link ReplayAction}s that form the initial-state snapshot for a
 * live {@link Player}.
 *
 * <p>This class is NMS-free; all version-specific work is delegated to the active
 * {@link VersionAdapter}.  The canonical action order is:
 * <ol>
 *   <li>{@code flashback:action/configuration_packet} — features / registry / tags
 *       (from {@link VersionAdapter#configActions})</li>
 *   <li>{@code flashback:action/game_packet} — {@code ClientboundLoginPacket}
 *       (from {@link VersionAdapter#loginAction})</li>
 *   <li>{@code flashback:action/create_local_player} — Flashback synthetic
 *       (from {@link CreateLocalPlayerAction#payload}, assembled here)</li>
 *   <li>{@code flashback:action/game_packet} — position, player-info, chunks
 *       (from {@link VersionAdapter#postLoginActions})</li>
 * </ol>
 *
 * <p><strong>Threading:</strong> {@link #configActions(Player)},
 * {@link #dynamicActions(Player)}, and {@link #build(Player)} MUST be called on the player's
 * region thread (Folia) or the main thread (Paper).
 */
public final class SnapshotBuilder {

    // Kept for ClipManager compatibility: it separately caches configActions and calls
    // dynamicActions for keyframe rotation.
    private SnapshotBuilder() {}

    /**
     * Configuration-phase snapshot actions (features / registry / tags).
     *
     * <p>Delegates to {@link VersionAdapter#configActions(Player)}.
     */
    public static List<ReplayAction> configActions(Player player) {
        return VersionAdapters.current().configActions(player);
    }

    /**
     * Dynamic (play-phase) snapshot actions: login, create_local_player, position, player-info,
     * chunks — in that order.
     *
     * <p>Equivalent to what the adapter's {@code loginAction} + {@code CreateLocalPlayerAction} +
     * {@code postLoginActions} produce when assembled by {@link #build(Player)}.
     */
    public static List<ReplayAction> dynamicActions(Player player) {
        VersionAdapter adapter = VersionAdapters.current();
        List<ReplayAction> actions = new ArrayList<>();
        // login
        actions.addAll(adapter.loginAction(player));
        // create_local_player (Bukkit-only, no NMS)
        actions.add(new ReplayAction(
                CreateLocalPlayerAction.IDENTIFIER,
                CreateLocalPlayerAction.payload(player)));
        // position + player-info + chunks
        actions.addAll(adapter.postLoginActions(player));
        return actions;
    }

    /**
     * Builds the complete ordered snapshot: {@link #configActions} followed by
     * {@link #dynamicActions}.
     *
     * <p>Must be called on the player's region thread.
     */
    public static List<ReplayAction> build(Player player) {
        List<ReplayAction> actions = new ArrayList<>();
        actions.addAll(configActions(player));
        actions.addAll(dynamicActions(player));
        return actions;
    }
}
