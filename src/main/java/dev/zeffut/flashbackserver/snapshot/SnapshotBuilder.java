package dev.zeffut.flashbackserver.snapshot;

import dev.zeffut.flashbackserver.format.ReplayAction;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Synthesises the ordered list of {@link ReplayAction}s that form the initial-state snapshot for a
 * live {@link Player}.
 *
 * <p>The actions are produced in the following order, matching what a joining client would receive:
 * <ol>
 *   <li>{@code flashback:action/game_packet} — {@link ClientboundLoginPacket}</li>
 *   <li>{@code flashback:action/create_local_player} — Flashback synthetic</li>
 *   <li>{@code flashback:action/game_packet} — {@link ClientboundPlayerPositionPacket}</li>
 *   <li>{@code flashback:action/game_packet} — {@link ClientboundPlayerInfoUpdatePacket}</li>
 *   <li>{@code flashback:action/game_packet} — {@link ClientboundLevelChunkWithLightPacket}
 *       for the chunk at the player's position (the player's current chunk only — loading the full
 *       view-distance radius is a fidelity follow-up)</li>
 * </ol>
 *
 * <p><strong>Threading:</strong> this method reads chunk data and entity state and MUST be called
 * on the player's region thread (Folia) or the main thread (standard Paper). The caller is
 * responsible for scheduling accordingly.
 *
 * <p><strong>TODO (follow-up):</strong> Full registry synchronisation via
 * {@code SynchronizeRegistriesTask} (CONFIGURATION-phase packets) is not yet emitted. If the
 * Flashback client requires registry data before it can render the login packet, that must be added
 * in Task 4 verification. For R3a the login + create_local_player + position + player-info +
 * one-chunk floor is the target.
 *
 * <p>All NMS access is confined to this package.
 */
public final class SnapshotBuilder {

    /** The Flashback game-packet action identifier. */
    private static final String GAME_PACKET = "flashback:action/game_packet";

    private SnapshotBuilder() {}

    /**
     * Builds the ordered initial-state snapshot actions for {@code player}.
     *
     * @param player the online Bukkit player
     * @return an ordered, mutable list of {@link ReplayAction}s ready to be written as a snapshot
     *         chunk
     * @throws IllegalArgumentException if {@code player} is not a {@link CraftPlayer}
     */
    public static List<ReplayAction> build(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalArgumentException(
                    "Expected CraftPlayer but got " + player.getClass().getName());
        }

        ServerPlayer sp = craft.getHandle();
        ServerLevel level = sp.serverLevel();
        MinecraftServer server = sp.getServer();

        List<ReplayAction> actions = new ArrayList<>();

        // 1. Login packet
        ClientboundLoginPacket loginPacket = new ClientboundLoginPacket(
                sp.getId(),
                server.isHardcore(),
                server.levelKeys(),
                server.getPlayerList().getMaxPlayers(),
                server.getPlayerList().getViewDistance(),
                server.getPlayerList().getSimulationDistance(),
                false,   // reducedDebugInfo — mirror gamerule for production; false is safe for R3a
                true,    // showDeathScreen
                false,   // doLimitedCrafting
                sp.createCommonSpawnInfo(level),
                true     // enforcesSecureChat
        );
        actions.add(new ReplayAction(GAME_PACKET, PacketSerializer.encodeGamePacket(sp, loginPacket)));

        // 2. create_local_player (Flashback synthetic action — no game_packet wrapper)
        actions.add(new ReplayAction(
                CreateLocalPlayerAction.IDENTIFIER,
                CreateLocalPlayerAction.payload(sp)));

        // 3. Position
        ClientboundPlayerPositionPacket positionPacket = ClientboundPlayerPositionPacket.of(
                sp.getId(),
                PositionMoveRotation.of(sp),
                Set.of());
        actions.add(new ReplayAction(GAME_PACKET, PacketSerializer.encodeGamePacket(sp, positionPacket)));

        // 4. Player info (ADD_PLAYER + UPDATE_LISTED)
        ClientboundPlayerInfoUpdatePacket playerInfoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
                List.of(sp));
        actions.add(new ReplayAction(GAME_PACKET, PacketSerializer.encodeGamePacket(sp, playerInfoPacket)));

        // 5. Chunk at player's position
        // Must run on the owning region thread — the caller guarantees this.
        ClientboundLevelChunkWithLightPacket chunkPacket =
                new ClientboundLevelChunkWithLightPacket(
                        level.getChunkAt(sp.blockPosition()),
                        level.getLightEngine(),
                        null,
                        null);
        actions.add(new ReplayAction(GAME_PACKET, PacketSerializer.encodeGamePacket(sp, chunkPacket)));

        return actions;
    }
}
