package dev.zeffut.flashbackserver.format;

import java.util.Map;
import java.util.Optional;

/**
 * Protocol-version-keyed table of clientbound PLAY packet ids.
 *
 * <p>Add a new row to {@link #TABLE} whenever a new Minecraft protocol version is supported.
 * The table is consulted by {@link FlashbackValidator#validateRenderable} to decide which
 * id-specific checks to apply; an absent entry causes graceful degradation (agnostic-floor
 * checks only, with an informational problem noting the unknown protocol).
 */
public final class PacketIds {

    /**
     * Packet id set for a single Minecraft protocol version.
     *
     * @param login      clientbound PLAY LoginPacket id
     * @param position   clientbound PLAY PlayerPositionPacket id
     * @param chunk      clientbound PLAY LevelChunkWithLightPacket id
     * @param playerInfo clientbound PLAY PlayerInfoUpdatePacket id
     */
    public record Ids(int login, int position, int chunk, int playerInfo) {}

    /**
     * Protocol version → clientbound PLAY packet ids.
     * 770 = MC 1.21.5 (confirmed via docs/research/r3-spike.md). Add rows per supported version.
     */
    private static final Map<Integer, Ids> TABLE = Map.of(
        770, new Ids(43, 65, 39, 63)
    );

    private PacketIds() {}

    /**
     * Returns the {@link Ids} for the given Minecraft protocol version, or
     * {@link Optional#empty()} if the version is not in the table.
     */
    public static Optional<Ids> forProtocol(int protocolVersion) {
        return Optional.ofNullable(TABLE.get(protocolVersion));
    }
}
