package dev.zeffut.flashbackserver.snapshot;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.zeffut.flashbackserver.format.VarCodec;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>GameProfile — variable length (manual MC wire format)</li>
 *   <li>gameModeId — varint</li>
 * </ol>
 *
 * <p>Total fixed prefix (UUID + positions + rotations + velocity): 76 bytes.
 *
 * <p>Uses only the Bukkit/Paper API — no {@code net.minecraft} imports.
 */
public final class CreateLocalPlayerAction {

    /** The Flashback action identifier for the create-local-player synthetic action. */
    public static final String IDENTIFIER = "flashback:action/create_local_player";

    private CreateLocalPlayerAction() {}

    /**
     * Builds the payload from a live Bukkit {@link Player}.
     *
     * @param player the online Bukkit player
     * @return the raw payload bytes
     */
    public static byte[] payload(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        UUID profileId = profile.getId();
        if (profileId == null) {
            profileId = player.getUniqueId();
        }
        String profileName = profile.getName();
        if (profileName == null) {
            profileName = player.getName();
        }

        // Convert Paper ProfileProperty → String[] triples [name, value, signature]
        List<String[]> props = new ArrayList<>();
        for (ProfileProperty prop : profile.getProperties()) {
            props.add(new String[]{prop.getName(), prop.getValue(), prop.getSignature()});
        }

        return build(
                player.getUniqueId(),
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ(),
                player.getLocation().getPitch(),
                player.getLocation().getYaw(),
                player.getLocation().getYaw(),   // yHeadRot = yaw (same as Flashback/NMS convention)
                player.getVelocity().getX(),
                player.getVelocity().getY(),
                player.getVelocity().getZ(),
                profileId,
                profileName,
                props,
                gameModeId(player.getGameMode()));
    }

    /**
     * Builds the payload from explicit primitive values plus a manually specified GameProfile.
     * Package-private so unit tests can assert the exact byte sequence for a known fixed input.
     *
     * @param uuid         player UUID
     * @param x            position x
     * @param y            position y
     * @param z            position z
     * @param xRot         pitch rotation
     * @param yRot         yaw rotation
     * @param yHeadRot     head yaw rotation
     * @param vx           velocity x
     * @param vy           velocity y
     * @param vz           velocity z
     * @param profileId    GameProfile UUID
     * @param profileName  GameProfile name
     * @param props        profile properties as {@code [name, value, signature]} arrays
     *                     (signature element may be {@code null})
     * @param gameModeId   vanilla game-mode id (SURVIVAL=0, CREATIVE=1, ADVENTURE=2, SPECTATOR=3)
     * @return the raw payload bytes
     */
    static byte[] build(
            UUID uuid,
            double x, double y, double z,
            float xRot, float yRot, float yHeadRot,
            double vx, double vy, double vz,
            UUID profileId, String profileName, List<String[]> props,
            int gameModeId) {

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(76 + 64);
            DataOutputStream dos = new DataOutputStream(baos);

            // 1. UUID (msb first, lsb second)
            dos.writeLong(uuid.getMostSignificantBits());
            dos.writeLong(uuid.getLeastSignificantBits());

            // 2. Position
            dos.writeDouble(x);
            dos.writeDouble(y);
            dos.writeDouble(z);

            // 3. Rotations
            dos.writeFloat(xRot);
            dos.writeFloat(yRot);
            dos.writeFloat(yHeadRot);

            // 4. Velocity
            dos.writeDouble(vx);
            dos.writeDouble(vy);
            dos.writeDouble(vz);

            // 5. GameProfile (manual MC wire format)
            writeGameProfile(dos, profileId, profileName, props);

            // 6. gameModeId as varint
            VarCodec.writeVarInt(dos, gameModeId);

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws — propagate as unchecked if it somehow does
            throw new RuntimeException("Failed to build create_local_player payload", e);
        }
    }

    /**
     * Serializes a GameProfile in Minecraft wire format (matches
     * {@code ByteBufCodecs.GAME_PROFILE}):
     * <ol>
     *   <li>UUID — two longs (msb, lsb)</li>
     *   <li>name — varint-length-prefixed UTF-8 string</li>
     *   <li>property count — varint</li>
     *   <li>per property: name string, value string, optional-signature boolean + string</li>
     * </ol>
     */
    private static void writeGameProfile(
            DataOutputStream out,
            UUID id,
            String name,
            List<String[]> props) throws IOException {

        // UUID
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());

        // Name
        VarCodec.writeString(out, name);

        // Properties
        VarCodec.writeVarInt(out, props.size());
        for (String[] prop : props) {
            String propName = prop[0];
            String propValue = prop[1];
            String propSignature = prop.length > 2 ? prop[2] : null;

            VarCodec.writeString(out, propName);
            VarCodec.writeString(out, propValue);
            // Optional signature
            boolean hasSig = propSignature != null;
            out.writeBoolean(hasSig);
            if (hasSig) {
                VarCodec.writeString(out, propSignature);
            }
        }
    }

    /**
     * Writes only the fixed-size prefix (UUID + positions + rotations + velocity) using plain Java
     * I/O. Exposed for unit tests that want to verify the 76-byte field order without needing a
     * GameProfile or a server.
     *
     * @return a 76-byte array
     */
    public static byte[] primitivePrefix(
            UUID uuid,
            double x, double y, double z,
            float xRot, float yRot, float yHeadRot,
            double vx, double vy, double vz) {

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(76);
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeLong(uuid.getMostSignificantBits());
            dos.writeLong(uuid.getLeastSignificantBits());
            dos.writeDouble(x);
            dos.writeDouble(y);
            dos.writeDouble(z);
            dos.writeFloat(xRot);
            dos.writeFloat(yRot);
            dos.writeFloat(yHeadRot);
            dos.writeDouble(vx);
            dos.writeDouble(vy);
            dos.writeDouble(vz);

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write primitive prefix", e);
        }
    }

    /**
     * Maps a Bukkit {@link GameMode} to the vanilla game-mode id used in MC wire format.
     */
    private static int gameModeId(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> 0;
            case CREATIVE -> 1;
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
        };
    }
}
