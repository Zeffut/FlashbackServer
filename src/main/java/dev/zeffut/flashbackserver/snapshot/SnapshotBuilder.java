package dev.zeffut.flashbackserver.snapshot;

import dev.zeffut.flashbackserver.format.ReplayAction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.chunk.LevelChunk;
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
 *   <li>{@code flashback:action/configuration_packet} — {@code ClientboundUpdateEnabledFeaturesPacket}</li>
 *   <li>{@code flashback:action/configuration_packet} — {@code ClientboundSelectKnownPacks} (from
 *       {@code SynchronizeRegistriesTask.start})</li>
 *   <li>{@code flashback:action/configuration_packet} — {@code ClientboundRegistryDataPacket} ×N
 *       (from {@code SynchronizeRegistriesTask.handleResponse(List.of())})</li>
 *   <li>{@code flashback:action/configuration_packet} — {@code ClientboundUpdateTagsPacket}</li>
 *   <li>{@code flashback:action/game_packet} — {@link ClientboundLoginPacket}</li>
 *   <li>{@code flashback:action/create_local_player} — Flashback synthetic</li>
 *   <li>{@code flashback:action/game_packet} — {@link ClientboundPlayerPositionPacket}</li>
 *   <li>{@code flashback:action/game_packet} — {@link ClientboundPlayerInfoUpdatePacket}</li>
 *   <li>{@code flashback:action/game_packet} — {@link ClientboundLevelChunkWithLightPacket} for each
 *       loaded chunk within {@link #SNAPSHOT_CHUNK_RADIUS} of the player's chunk position</li>
 * </ol>
 *
 * <p><strong>Threading:</strong> {@link #configActions(Player)} and {@link #dynamicActions(Player)}
 * (and therefore {@link #build(Player)}) MUST be called on the player's region thread (Folia) or
 * the main thread (standard Paper). The caller is responsible for scheduling accordingly.
 *
 * <p>All NMS access is confined to this package.
 */
public final class SnapshotBuilder {

    /** The Flashback game-packet action identifier. */
    private static final String GAME_PACKET = "flashback:action/game_packet";

    /** The Flashback configuration-packet action identifier. */
    private static final String CONFIG_PACKET = "flashback:action/configuration_packet";

    /**
     * Maximum chunk radius (in chunks) around the player's chunk position to include in the
     * snapshot. Combined with the server's actual view-distance via
     * {@code Math.min(viewDistance, SNAPSHOT_CHUNK_RADIUS)}, so the actual radius is at most this
     * value.
     */
    static final int SNAPSHOT_CHUNK_RADIUS = 8;

    private SnapshotBuilder() {}

    /**
     * Builds the configuration-phase snapshot actions for {@code player} (step 1 of the full
     * snapshot). These actions are session-invariant and correspond to the
     * {@code flashback:action/configuration_packet} entries.
     *
     * <p><strong>Threading:</strong> must be called on the player's region thread.
     *
     * @param player the online Bukkit player
     * @return an ordered, mutable list of configuration-phase {@link ReplayAction}s
     * @throws IllegalArgumentException if {@code player} is not a {@link CraftPlayer}
     */
    public static List<ReplayAction> configActions(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalArgumentException(
                    "Expected CraftPlayer but got " + player.getClass().getName());
        }

        ServerPlayer sp = craft.getHandle();
        MinecraftServer server = sp.getServer();

        List<ReplayAction> actions = new ArrayList<>();

        // ── CONFIGURATION phase ────────────────────────────────────────────────────────────────

        // 1a. Enabled features
        ClientboundUpdateEnabledFeaturesPacket featuresPacket =
                new ClientboundUpdateEnabledFeaturesPacket(
                        FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures()));
        actions.add(new ReplayAction(CONFIG_PACKET,
                PacketSerializer.encodeConfigPacket(featuresPacket)));

        // 1b. Registry data via SynchronizeRegistriesTask (Arcade trick: drive handleResponse
        //     ourselves with an empty client list to force full inline data — no real client needed).
        var requestedPacks = server.getResourceManager().listPacks()
                .flatMap(p -> p.knownPackInfo().stream())
                .toList();

        SynchronizeRegistriesTask task =
                new SynchronizeRegistriesTask(requestedPacks, server.registries());

        // start() emits ClientboundSelectKnownPacks (id=14) — include it (Flashback records it).
        // The sink is Consumer<Packet<?>> at the call-site; encodeConfigPacket takes Packet<?> and
        // performs the unchecked cast internally.
        List<Packet<?>> configOut = new ArrayList<>();
        task.start(configOut::add);
        for (Packet<?> p : configOut) {
            actions.add(new ReplayAction(CONFIG_PACKET,
                    PacketSerializer.encodeConfigPacket(p)));
        }

        // handleResponse(List.of()) -> 21 × ClientboundRegistryDataPacket (full inline data)
        List<Packet<?>> registryOut = new ArrayList<>();
        task.handleResponse(List.of(), registryOut::add);
        for (Packet<?> p : registryOut) {
            actions.add(new ReplayAction(CONFIG_PACKET,
                    PacketSerializer.encodeConfigPacket(p)));
        }

        // 1c. Tags
        ClientboundUpdateTagsPacket tagsPacket = new ClientboundUpdateTagsPacket(
                TagNetworkSerialization.serializeTagsToNetwork(server.registries()));
        actions.add(new ReplayAction(CONFIG_PACKET,
                PacketSerializer.encodeConfigPacket(tagsPacket)));

        return actions;
    }

    /**
     * Builds the dynamic (play-phase) snapshot actions for {@code player} (steps 2–5 of the full
     * snapshot). These actions cover login, local-player creation, position, player-info, and
     * loaded chunks.
     *
     * <p><strong>Threading:</strong> must be called on the player's region thread, as it reads
     * chunk data and entity state.
     *
     * @param player the online Bukkit player
     * @return an ordered, mutable list of play-phase {@link ReplayAction}s
     * @throws IllegalArgumentException if {@code player} is not a {@link CraftPlayer}
     */
    public static List<ReplayAction> dynamicActions(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalArgumentException(
                    "Expected CraftPlayer but got " + player.getClass().getName());
        }

        ServerPlayer sp = craft.getHandle();
        ServerLevel level = sp.serverLevel();
        MinecraftServer server = sp.getServer();

        List<ReplayAction> actions = new ArrayList<>();

        // ── PLAY phase ─────────────────────────────────────────────────────────────────────────

        // 2. Login packet
        ClientboundLoginPacket loginPacket = new ClientboundLoginPacket(
                sp.getId(),
                server.isHardcore(),
                server.levelKeys(),
                server.getPlayerList().getMaxPlayers(),
                server.getPlayerList().getViewDistance(),
                server.getPlayerList().getSimulationDistance(),
                false,   // reducedDebugInfo — false is the safe default
                true,    // showDeathScreen
                false,   // doLimitedCrafting
                sp.createCommonSpawnInfo(level),
                true     // enforcesSecureChat
        );
        actions.add(new ReplayAction(GAME_PACKET, PacketSerializer.encodeGamePacket(sp, loginPacket)));

        // 3. create_local_player (Flashback synthetic action — no game_packet wrapper)
        actions.add(new ReplayAction(
                CreateLocalPlayerAction.IDENTIFIER,
                CreateLocalPlayerAction.payload(sp)));

        // 4. Position
        ClientboundPlayerPositionPacket positionPacket = ClientboundPlayerPositionPacket.of(
                sp.getId(),
                PositionMoveRotation.of(sp),
                Set.of());
        actions.add(new ReplayAction(GAME_PACKET, PacketSerializer.encodeGamePacket(sp, positionPacket)));

        // 5. Player info (ADD_PLAYER + UPDATE_LISTED)
        ClientboundPlayerInfoUpdatePacket playerInfoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
                List.of(sp));
        actions.add(new ReplayAction(GAME_PACKET, PacketSerializer.encodeGamePacket(sp, playerInfoPacket)));

        // 6. All loaded chunks within view-distance (capped at SNAPSHOT_CHUNK_RADIUS).
        //    Must run on the owning region thread — the caller guarantees this.
        int viewDist = server.getPlayerList().getViewDistance();
        int radius = Math.min(viewDist, SNAPSHOT_CHUNK_RADIUS);
        int cx0 = sp.chunkPosition().x;
        int cz0 = sp.chunkPosition().z;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = cx0 + dx;
                int cz = cz0 + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue; // not loaded — skip
                }
                ClientboundLevelChunkWithLightPacket chunkPacket =
                        new ClientboundLevelChunkWithLightPacket(
                                chunk,
                                level.getLightEngine(),
                                null,
                                null);
                actions.add(new ReplayAction(GAME_PACKET,
                        PacketSerializer.encodeGamePacket(sp, chunkPacket)));
            }
        }

        return actions;
    }

    /**
     * Builds the ordered initial-state snapshot actions for {@code player}.
     *
     * <p>Equivalent to concatenating {@link #configActions(Player)} followed by
     * {@link #dynamicActions(Player)}.
     *
     * <p><strong>Threading:</strong> must be called on the player's region thread.
     *
     * @param player the online Bukkit player
     * @return an ordered, mutable list of {@link ReplayAction}s ready to be written as a snapshot
     *         chunk
     * @throws IllegalArgumentException if {@code player} is not a {@link CraftPlayer}
     */
    public static List<ReplayAction> build(Player player) {
        List<ReplayAction> actions = new ArrayList<>();
        actions.addAll(configActions(player));
        actions.addAll(dynamicActions(player));
        return actions;
    }
}
