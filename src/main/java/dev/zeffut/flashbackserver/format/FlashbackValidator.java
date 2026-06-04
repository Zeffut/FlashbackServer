package dev.zeffut.flashbackserver.format;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class FlashbackValidator {

    /**
     * Validation result. Fields other than {@code problems} are meaningful only when no
     * container-level failure occurred (on an unreadable container they default to 0).
     * {@code problems} is an unmodifiable list.
     */
    public record Report(boolean valid, List<String> problems, int totalTicks, int chunkCount) {}

    // Clientbound PLAY packet ids for Paper 1.21.5 (confirmed via docs/research/r3-spike.md).
    /** Paper 1.21.5 clientbound PLAY — LoginPacket id. */
    private static final int PACKET_ID_LOGIN          = 43;
    /** Paper 1.21.5 clientbound PLAY — PlayerPositionPacket id. */
    private static final int PACKET_ID_POSITION       = 65;
    /** Paper 1.21.5 clientbound PLAY — LevelChunkWithLightPacket id. */
    private static final int PACKET_ID_LEVEL_CHUNK    = 39;
    /** Paper 1.21.5 clientbound PLAY — PlayerInfoUpdatePacket id (nice-to-have). */
    private static final int PACKET_ID_PLAYER_INFO    = 63;

    /** Identifier for the Flashback synthetic action that spawns the local player. */
    private static final String ID_CREATE_LOCAL_PLAYER = "flashback:action/create_local_player";
    /** Identifier for raw game packets captured from the wire. */
    private static final String ID_GAME_PACKET         = "flashback:action/game_packet";
    /** Identifier for CONFIGURATION-phase packets (registry data, features, tags). */
    private static final String ID_CONFIG_PACKET       = "flashback:action/configuration_packet";

    private FlashbackValidator() {}

    public static Report validate(Path file) {
        List<String> problems = new ArrayList<>();
        int totalTicks = 0;
        int chunkCount = 0;

        try (var reader = FlashbackContainer.open(file)) {
            Set<String> entries = reader.entryNames();
            if (!entries.contains("metadata.json")) {
                problems.add("missing metadata.json");
                return new Report(false, List.copyOf(problems), 0, 0);
            }

            FlashbackMeta meta = reader.readMetadata();
            totalTicks = meta.totalTicks;
            chunkCount = meta.chunks.size();

            long tickSum = 0;
            for (var entry : meta.chunks.entrySet()) {
                String name = entry.getKey();
                if (!entries.contains(name)) {
                    problems.add("declared chunk not present: " + name);
                    continue;
                }
                try {
                    ChunkReader.Result result = ChunkReader.read(reader.readChunk(name));
                    long ticks = result.streamActions().stream()
                        .filter(a -> a.identifier().equals("flashback:action/next_tick")).count();
                    if (ticks != entry.getValue().duration) {
                        problems.add("chunk " + name + " tick mismatch: declared "
                            + entry.getValue().duration + ", found " + ticks);
                    }
                    tickSum += ticks;
                } catch (Exception e) {
                    problems.add("chunk " + name + " unparseable: " + e.getMessage());
                }
            }
            if (tickSum != meta.totalTicks) {
                problems.add("total_ticks mismatch: declared " + meta.totalTicks + ", summed " + tickSum);
            }
        } catch (Exception e) {
            problems.add("container unreadable: " + e.getMessage());
        }

        return new Report(problems.isEmpty(), List.copyOf(problems), totalTicks, chunkCount);
    }

    /**
     * Extends {@link #validate} with a snapshot-content check on the FIRST declared chunk.
     *
     * <p>The snapshot of the first chunk must contain the "renderable floor":
     * <ul>
     *   <li>At least one {@code flashback:action/configuration_packet} action (registry/config
     *       data presence).</li>
     *   <li>At least one {@code flashback:action/create_local_player} action.</li>
     *   <li>A login game_packet (leading varint id == {@value #PACKET_ID_LOGIN}).</li>
     *   <li>A position game_packet (leading varint id == {@value #PACKET_ID_POSITION}).</li>
     *   <li>At least one chunk game_packet (leading varint id == {@value #PACKET_ID_LEVEL_CHUNK}).</li>
     *   <li>A player-info game_packet (leading varint id == {@value #PACKET_ID_PLAYER_INFO}).</li>
     * </ul>
     *
     * <p>Returns {@code valid=false} listing any missing required elements if the structural
     * {@link #validate} already failed, or if the snapshot is missing any of the above.
     */
    public static Report validateRenderable(Path file) {
        // First run structural validation; collect its problems.
        Report structural = validate(file);
        List<String> problems = new ArrayList<>(structural.problems());

        // Only proceed with snapshot checks if the container was at least partially readable.
        try (var reader = FlashbackContainer.open(file)) {
            FlashbackMeta meta = reader.readMetadata();
            if (meta.chunks.isEmpty()) {
                problems.add("no chunks declared — cannot check snapshot");
                return new Report(false, List.copyOf(problems), structural.totalTicks(), structural.chunkCount());
            }

            // Pick the first declared chunk by insertion order.
            String firstChunkName = meta.chunks.keySet().iterator().next();
            byte[] chunkBytes = reader.readChunk(firstChunkName);
            ChunkReader.Result result = ChunkReader.read(chunkBytes);
            List<ReplayAction> snapshot = result.snapshotActions();

            boolean hasConfigPacket      = false;
            boolean hasCreateLocalPlayer = false;
            boolean hasLogin             = false;
            boolean hasPosition          = false;
            boolean hasChunk             = false;
            boolean hasPlayerInfo        = false;

            for (ReplayAction action : snapshot) {
                if (ID_CONFIG_PACKET.equals(action.identifier())) {
                    hasConfigPacket = true;
                } else if (ID_CREATE_LOCAL_PLAYER.equals(action.identifier())) {
                    hasCreateLocalPlayer = true;
                } else if (ID_GAME_PACKET.equals(action.identifier())) {
                    int packetId = readLeadingVarInt(action.payload());
                    if (packetId == PACKET_ID_LOGIN)            hasLogin       = true;
                    else if (packetId == PACKET_ID_POSITION)    hasPosition    = true;
                    else if (packetId == PACKET_ID_LEVEL_CHUNK) hasChunk       = true;
                    else if (packetId == PACKET_ID_PLAYER_INFO) hasPlayerInfo  = true;
                }
            }

            if (!hasConfigPacket)
                problems.add("snapshot missing configuration data");
            if (!hasCreateLocalPlayer)
                problems.add("snapshot missing: flashback:action/create_local_player");
            if (!hasLogin)
                problems.add("snapshot missing: login game_packet (id=" + PACKET_ID_LOGIN + ")");
            if (!hasPosition)
                problems.add("snapshot missing: position game_packet (id=" + PACKET_ID_POSITION + ")");
            if (!hasChunk)
                problems.add("snapshot missing: level-chunk game_packet (id=" + PACKET_ID_LEVEL_CHUNK + ")");
            if (!hasPlayerInfo)
                problems.add("snapshot missing: player-info game_packet (id=" + PACKET_ID_PLAYER_INFO + ")");

        } catch (Exception e) {
            // If structural validation already caught this we don't double-report the container error.
            if (structural.valid()) {
                problems.add("snapshot check failed: " + e.getMessage());
            }
        }

        return new Report(problems.isEmpty(), List.copyOf(problems), structural.totalTicks(), structural.chunkCount());
    }

    /**
     * Reads the leading VarInt (the packet id) from a game_packet payload.
     * Returns -1 if the payload is empty or malformed.
     *
     * <p>Packet payloads captured at {@code addBefore("encoder")} start with the varint packet id
     * followed by the field bytes — exactly the shape observed in docs/research/r3-spike.md.
     */
    private static int readLeadingVarInt(byte[] payload) {
        if (payload == null || payload.length == 0) return -1;
        try {
            return VarCodec.readVarInt(new DataInputStream(new ByteArrayInputStream(payload)));
        } catch (IOException e) {
            return -1;
        }
    }
}
