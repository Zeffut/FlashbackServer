package dev.zeffut.flashbackserver.snapshot;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Builds the payload for the Flashback synthetic action
 * {@code flashback:action/create_local_player}.
 *
 * <p>Field order (matches Flashback's reader exactly):
 * <ol>
 *   <li>UUID — 16 bytes (two longs: msb, lsb)</li>
 *   <li>x, y, z — 3 doubles (24 bytes)</li>
 *   <li>xRot, yRot, yHeadRot — 3 floats (12 bytes)</li>
 *   <li>velocity x, y, z — 3 doubles (24 bytes)</li>
 *   <li>GameProfile — variable length (via {@code ByteBufCodecs.GAME_PROFILE})</li>
 *   <li>gameModeId — varint</li>
 * </ol>
 *
 * <p>Total fixed prefix (UUID + positions + rotations + velocity): 76 bytes.
 *
 * <p>All NMS access is confined to this package.
 */
public final class CreateLocalPlayerAction {

    /** The Flashback action identifier for the create-local-player synthetic action. */
    public static final String IDENTIFIER = "flashback:action/create_local_player";

    private CreateLocalPlayerAction() {}

    /**
     * Builds the payload from a live {@link ServerPlayer}.
     *
     * @param sp the server player
     * @return the raw payload bytes
     */
    public static byte[] payload(ServerPlayer sp) {
        var velocity = sp.getDeltaMovement();
        return payload(
                sp.getUUID(),
                sp.getX(), sp.getY(), sp.getZ(),
                sp.getXRot(), sp.getYRot(), sp.getYHeadRot(),
                velocity.x, velocity.y, velocity.z,
                sp.getGameProfile(),
                sp.gameMode.getGameModeForPlayer().getId());
    }

    /**
     * Builds the payload from primitive values. Intended for unit testing the field order without
     * a running server.
     *
     * @param uuid       player UUID
     * @param x          position x
     * @param y          position y
     * @param z          position z
     * @param xRot       yaw rotation
     * @param yRot       pitch rotation
     * @param yHeadRot   head yaw rotation
     * @param vx         velocity x
     * @param vy         velocity y
     * @param vz         velocity z
     * @param profile    the player's {@link GameProfile} (with skin properties)
     * @param gameModeId the vanilla {@link net.minecraft.world.level.GameType} id
     * @return the raw payload bytes
     */
    public static byte[] payload(
            UUID uuid,
            double x, double y, double z,
            float xRot, float yRot, float yHeadRot,
            double vx, double vy, double vz,
            GameProfile profile,
            int gameModeId) {

        // Use a RegistryFriendlyByteBuf so that ByteBufCodecs.GAME_PROFILE (a StreamCodec) can
        // operate on it. RegistryFriendlyByteBuf.decorator applied to a plain heap buffer gives us
        // all standard write helpers plus the codec support.
        ByteBuf inner = Unpooled.buffer(76 + 64); // 76-byte fixed prefix + room for profile/varint
        RegistryFriendlyByteBuf buf =
                RegistryFriendlyByteBuf.decorator(
                        net.minecraft.core.RegistryAccess.EMPTY).apply(inner);
        try {
            // 1. UUID (msb first, lsb second — matches writeUUID)
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
            // 2. position
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            // 3. rotations
            buf.writeFloat(xRot);
            buf.writeFloat(yRot);
            buf.writeFloat(yHeadRot);
            // 4. velocity
            buf.writeDouble(vx);
            buf.writeDouble(vy);
            buf.writeDouble(vz);
            // 5. GameProfile
            ByteBufCodecs.GAME_PROFILE.encode(buf, profile);
            // 6. gameModeId as varint
            buf.writeVarInt(gameModeId);

            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * Writes only the fixed-size prefix (UUID + positions + rotations + velocity) into a plain
     * {@link ByteBuf}. Exposed for unit tests that want to verify the 76-byte field order without
     * needing {@code GAME_PROFILE} codec or a registry.
     *
     * @return a 76-byte array
     */
    public static byte[] primitivePrefix(
            UUID uuid,
            double x, double y, double z,
            float xRot, float yRot, float yHeadRot,
            double vx, double vy, double vz) {

        ByteBuf buf = Unpooled.buffer(76);
        try {
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(xRot);
            buf.writeFloat(yRot);
            buf.writeFloat(yHeadRot);
            buf.writeDouble(vx);
            buf.writeDouble(vy);
            buf.writeDouble(vz);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }
}
