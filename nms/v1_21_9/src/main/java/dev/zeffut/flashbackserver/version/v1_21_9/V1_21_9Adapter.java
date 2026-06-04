package dev.zeffut.flashbackserver.version.v1_21_9;

import dev.zeffut.flashbackserver.format.ReplayAction;
import dev.zeffut.flashbackserver.version.VersionAdapter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
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
 * {@link VersionAdapter} implementation for Minecraft 1.21.9.
 *
 * <p>All {@code net.minecraft.*} and {@code org.bukkit.craftbukkit.*} access in this project is
 * confined to this package ({@code version/v1_21_9/}).
 *
 * <h3>Snapshot ordering guarantee</h3>
 * The three snapshot-building methods produce actions in this order when assembled by core:
 * <ol>
 *   <li>{@link #configActions} — configuration_packet × N (features, registry, tags)</li>
 *   <li>{@link #loginAction} — game_packet (ClientboundLoginPacket)</li>
 *   <li>create_local_player — inserted by core's SnapshotBuilder, not this adapter</li>
 *   <li>{@link #postLoginActions} — position, player-info, chunks (game_packet × N)</li>
 * </ol>
 */
public final class V1_21_9Adapter implements VersionAdapter {

    /** Maximum chunk radius (in chunks) to include in the snapshot. */
    private static final int SNAPSHOT_CHUNK_RADIUS = 8;

    private static final String GAME_PACKET   = "flashback:action/game_packet";
    private static final String CONFIG_PACKET = "flashback:action/configuration_packet";
    private static final int MAX_PROBLEMS = 20;

    // ─── VersionAdapter: channel ──────────────────────────────────────────────

    @Override
    public Channel channelOf(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalStateException(
                    "Expected CraftPlayer but got " + player.getClass().getName());
        }
        ServerPlayer handle = craft.getHandle();
        ServerGamePacketListenerImpl gameListener = handle.connection;
        if (gameListener == null) {
            throw new IllegalStateException(
                    "ServerGamePacketListenerImpl (handle.connection) was null for player "
                    + player.getName());
        }
        Connection connection = gameListener.connection;
        if (connection == null) {
            throw new IllegalStateException(
                    "Connection (gameListener.connection) was null for player " + player.getName());
        }
        Channel channel = connection.channel;
        if (channel == null) {
            throw new IllegalStateException(
                    "Netty channel was null for player " + player.getName());
        }
        return channel;
    }

    // ─── VersionAdapter: versions ─────────────────────────────────────────────

    @Override
    public int protocolVersion() {
        return SharedConstants.getProtocolVersion();
    }

    @Override
    public int dataVersion() {
        return org.bukkit.Bukkit.getUnsafe().getDataVersion();
    }

    // ─── VersionAdapter: snapshot building ───────────────────────────────────

    /**
     * Configuration-phase actions: features → select-known-packs → registry-data × N → tags.
     * Must be called on the player's region thread.
     */
    @Override
    public List<ReplayAction> configActions(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalArgumentException(
                    "Expected CraftPlayer but got " + player.getClass().getName());
        }
        ServerPlayer sp = craft.getHandle();
        MinecraftServer server = sp.level().getServer();
        List<ReplayAction> actions = new ArrayList<>();

        // 1a. Enabled features
        ClientboundUpdateEnabledFeaturesPacket featuresPacket =
                new ClientboundUpdateEnabledFeaturesPacket(
                        FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures()));
        actions.add(new ReplayAction(CONFIG_PACKET, encodeConfigPacket(featuresPacket)));

        // 1b. Registry data via SynchronizeRegistriesTask
        var requestedPacks = server.getResourceManager().listPacks()
                .flatMap(p -> p.knownPackInfo().stream())
                .toList();
        SynchronizeRegistriesTask task =
                new SynchronizeRegistriesTask(requestedPacks, server.registries());

        List<Packet<?>> configOut = new ArrayList<>();
        task.start(configOut::add);
        for (Packet<?> p : configOut) {
            actions.add(new ReplayAction(CONFIG_PACKET, encodeConfigPacket(p)));
        }

        List<Packet<?>> registryOut = new ArrayList<>();
        task.handleResponse(List.of(), registryOut::add);
        for (Packet<?> p : registryOut) {
            actions.add(new ReplayAction(CONFIG_PACKET, encodeConfigPacket(p)));
        }

        // 1c. Tags
        ClientboundUpdateTagsPacket tagsPacket = new ClientboundUpdateTagsPacket(
                TagNetworkSerialization.serializeTagsToNetwork(server.registries()));
        actions.add(new ReplayAction(CONFIG_PACKET, encodeConfigPacket(tagsPacket)));

        return actions;
    }

    /**
     * One-element list containing the encoded {@code ClientboundLoginPacket} game_packet action.
     * Must be called on the player's region thread.
     */
    @Override
    public List<ReplayAction> loginAction(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalArgumentException(
                    "Expected CraftPlayer but got " + player.getClass().getName());
        }
        ServerPlayer sp = craft.getHandle();
        ServerLevel level = (ServerLevel) sp.level();
        MinecraftServer server = sp.level().getServer();

        ClientboundLoginPacket loginPacket = new ClientboundLoginPacket(
                sp.getId(),
                server.isHardcore(),
                server.levelKeys(),
                server.getPlayerList().getMaxPlayers(),
                server.getPlayerList().getViewDistance(),
                server.getPlayerList().getSimulationDistance(),
                false,   // reducedDebugInfo
                true,    // showDeathScreen
                false,   // doLimitedCrafting
                sp.createCommonSpawnInfo(level),
                true     // enforcesSecureChat
        );
        return List.of(new ReplayAction(GAME_PACKET, encodeGamePacket(sp, loginPacket)));
    }

    /**
     * Post-login PLAY actions (EXCLUDING create_local_player): position, player-info, chunks.
     * Must be called on the player's region thread.
     */
    @Override
    public List<ReplayAction> postLoginActions(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalArgumentException(
                    "Expected CraftPlayer but got " + player.getClass().getName());
        }
        ServerPlayer sp = craft.getHandle();
        ServerLevel level = (ServerLevel) sp.level();
        MinecraftServer server = sp.level().getServer();
        List<ReplayAction> actions = new ArrayList<>();

        // Position
        ClientboundPlayerPositionPacket positionPacket = ClientboundPlayerPositionPacket.of(
                sp.getId(),
                PositionMoveRotation.of(sp),
                Set.of());
        actions.add(new ReplayAction(GAME_PACKET, encodeGamePacket(sp, positionPacket)));

        // Player info (ADD_PLAYER + UPDATE_LISTED)
        ClientboundPlayerInfoUpdatePacket playerInfoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
                List.of(sp));
        actions.add(new ReplayAction(GAME_PACKET, encodeGamePacket(sp, playerInfoPacket)));

        // Chunks within view-distance (capped at SNAPSHOT_CHUNK_RADIUS)
        int viewDist = server.getPlayerList().getViewDistance();
        int radius = Math.min(viewDist, SNAPSHOT_CHUNK_RADIUS);
        int cx0 = sp.chunkPosition().x;
        int cz0 = sp.chunkPosition().z;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx0 + dx, cz0 + dz);
                if (chunk == null) continue;
                ClientboundLevelChunkWithLightPacket chunkPacket =
                        new ClientboundLevelChunkWithLightPacket(
                                chunk, level.getLightEngine(), null, null);
                actions.add(new ReplayAction(GAME_PACKET, encodeGamePacket(sp, chunkPacket)));
            }
        }

        return actions;
    }

    // ─── VersionAdapter: decode ───────────────────────────────────────────────

    @Override
    public DecodeResult decode(List<ReplayAction> snapshotActions, List<ReplayAction> streamActions) {
        // Build codecs once per call (acceptable — only called from /replay verify, not hot path).
        // We need a RegistryAccess to build the PLAY codec decorator. We cannot obtain it here
        // without a player, so we use the dedicated registryAccess() helper that reads from the
        // running server singleton — safe for a verify-only call path.
        RegistryAccess registryAccess = MinecraftServer.getServer().registryAccess();

        ProtocolInfo<ClientGamePacketListener> gameInfo =
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(registryAccess));
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec = gameInfo.codec();
        StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> configCodec =
                ConfigurationProtocols.CLIENTBOUND.codec();

        int decoded = 0;
        int errors = 0;
        List<String> problems = new ArrayList<>();

        List<ReplayAction> all = new ArrayList<>(snapshotActions.size() + streamActions.size());
        all.addAll(snapshotActions);
        all.addAll(streamActions);

        for (ReplayAction action : all) {
            final StreamCodec<ByteBuf, ?> codec;
            if (GAME_PACKET.equals(action.identifier())) {
                codec = gameCodec;
            } else if (CONFIG_PACKET.equals(action.identifier())) {
                codec = configCodec;
            } else {
                continue; // skip synthetic actions (create_local_player, next_tick, etc.)
            }

            ByteBuf buf = Unpooled.wrappedBuffer(action.payload());
            try {
                codec.decode(buf);
                if (buf.isReadable()) {
                    errors++;
                    if (problems.size() < MAX_PROBLEMS) {
                        problems.add("Trailing bytes after decode (" + action.identifier() + "): "
                                + buf.readableBytes() + " byte(s) unconsumed");
                    }
                } else {
                    decoded++;
                }
            } catch (Exception e) {
                errors++;
                if (problems.size() < MAX_PROBLEMS) {
                    problems.add("Decode error (" + action.identifier() + "): "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } finally {
                buf.release();
            }
        }

        return new DecodeResult(decoded, errors, List.copyOf(problems));
    }

    // ─── Private encode helpers ───────────────────────────────────────────────

    /**
     * Encodes a clientbound CONFIGURATION-phase packet to {@code varint id + payload} bytes.
     */
    @SuppressWarnings("unchecked")
    private static byte[] encodeConfigPacket(Packet<?> packet) {
        StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> codec =
                ConfigurationProtocols.CLIENTBOUND.codec();
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, (Packet<? super ClientConfigurationPacketListener>) packet);
            return ByteBufUtil.getBytes(buf);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to encode config packet " + packet.getClass().getSimpleName(), e);
        } finally {
            buf.release();
        }
    }

    /**
     * Encodes a clientbound PLAY packet using the player's registry access to
     * {@code varint id + payload} bytes.
     */
    private static byte[] encodeGamePacket(
            ServerPlayer sp,
            Packet<? super ClientGamePacketListener> packet) {
        ProtocolInfo<ClientGamePacketListener> info =
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(sp.registryAccess()));
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = info.codec();
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, packet);
            return ByteBufUtil.getBytes(buf);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to encode game packet " + packet.getClass().getSimpleName()
                    + " for player " + sp.getScoreboardName(), e);
        } finally {
            buf.release();
        }
    }
}
