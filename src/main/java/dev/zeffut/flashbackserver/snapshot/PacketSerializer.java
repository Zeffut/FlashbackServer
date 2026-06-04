package dev.zeffut.flashbackserver.snapshot;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.level.ServerPlayer;

/**
 * Serializes a clientbound PLAY {@link Packet} to {@code varint id + payload} bytes — the exact
 * byte shape that {@code flashback:action/game_packet} stores.
 *
 * <p>All NMS access is confined to this package.
 */
public final class PacketSerializer {

    private PacketSerializer() {}

    /**
     * Encodes a clientbound CONFIGURATION-phase {@link Packet} and returns the
     * {@code varint id + payload} bytes produced by the CONFIGURATION protocol codec.
     *
     * <p>{@code ConfigurationProtocols.CLIENTBOUND} is already bound over plain {@code FriendlyByteBuf}
     * and requires NO registry decorator (unlike the PLAY template). A single codec instance handles
     * all config and common-clientbound packet types; an unchecked cast bridges the
     * {@code Packet<ClientCommonPacketListener>} used by e.g. {@code ClientboundUpdateTagsPacket}
     * since the codec dispatches on {@code packet.type()}, not the static generic.
     *
     * @param packet a clientbound CONFIGURATION (or common) packet
     * @return the raw {@code id + payload} bytes
     * @throws RuntimeException if encoding fails
     */
    @SuppressWarnings("unchecked")
    public static byte[] encodeConfigPacket(Packet<?> packet) {
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
     * Encodes {@code packet} using the player's registry access and returns the
     * {@code varint id + payload} bytes produced by the PLAY protocol codec.
     *
     * <p>The codec is built fresh per call (one {@link ProtocolInfo} bind per registry-access).
     * This is acceptable for snapshot construction which happens once per recording start.
     *
     * @param sp     the live server player whose {@link net.minecraft.core.RegistryAccess} provides
     *               the registry-aware buf decorator
     * @param packet a clientbound PLAY packet
     * @return the raw {@code id + payload} bytes
     * @throws RuntimeException if encoding fails
     */
    public static byte[] encodeGamePacket(
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
                            + " for player " + sp.getScoreboardName(),
                    e);
        } finally {
            buf.release();
        }
    }
}
