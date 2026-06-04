package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FlashbackValidator#validateRenderable(Path)}.
 *
 * <p>Fixtures are built entirely in-process using {@link FlashbackContainer} +
 * {@link ChunkWriter} — no server is required.
 *
 * <p>Packet-id constants (Paper 1.21.5 clientbound PLAY) are verified in
 * docs/research/r3-spike.md:
 * <ul>
 *   <li>login id=43</li>
 *   <li>player-position id=65</li>
 *   <li>level-chunk-with-light id=39</li>
 *   <li>player-info-update id=63</li>
 * </ul>
 * Ids &lt; 128 fit in a single-byte VarInt, so the payload is just {@code {(byte) id, ...}}.
 */
class FlashbackValidatorRenderableTest {

    // Packet ids < 128 → 1-byte varint. Build payloads as {id_byte, dummy_bytes...}.
    private static final byte ID_LOGIN       = 43;
    private static final byte ID_POSITION    = 65;
    private static final byte ID_CHUNK       = 39;
    private static final byte ID_PLAYER_INFO = 63;

    private static final String GAME_PACKET         = "flashback:action/game_packet";
    private static final String CREATE_LOCAL_PLAYER = "flashback:action/create_local_player";
    private static final String CONFIG_PACKET       = "flashback:action/configuration_packet";
    private static final String NEXT_TICK           = "flashback:action/next_tick";

    /** Constructs a minimal game_packet ReplayAction whose payload starts with the given id byte. */
    private static ReplayAction gamePacket(byte id) {
        return new ReplayAction(GAME_PACKET, new byte[]{id, 0x01, 0x02});
    }

    /**
     * Writes a .flashback file with the given snapshot actions.
     * The stream section has 1 next_tick so structural validation passes (totalTicks == declared).
     */
    private Path writeFixture(Path dir, String name, List<ReplayAction> snapshotActions) throws Exception {
        Path file = dir.resolve(name + ".flashback");
        var meta = new FlashbackMeta();
        meta.name = name;
        meta.totalTicks = 1;
        meta.chunks.put("c0.flashback", new ChunkMeta(1));

        byte[] chunk = ChunkWriter.write(
            snapshotActions,
            List.of(new ReplayAction(NEXT_TICK, new byte[0]))
        );
        try (var w = FlashbackContainer.create(file)) {
            w.writeMetadata(meta);
            w.writeChunk("c0.flashback", chunk);
        }
        return file;
    }

    // -----------------------------------------------------------------------
    // Positive fixture: full renderable floor present
    // -----------------------------------------------------------------------

    @Test
    void acceptsFullRenderableSnapshot(@TempDir Path dir) throws Exception {
        List<ReplayAction> snapshot = List.of(
            new ReplayAction(CONFIG_PACKET, new byte[]{0x07, 0x01}), // minimal config_packet (registry data stub)
            gamePacket(ID_LOGIN),
            new ReplayAction(CREATE_LOCAL_PLAYER, new byte[32]), // minimal non-empty payload
            gamePacket(ID_POSITION),
            gamePacket(ID_PLAYER_INFO),
            gamePacket(ID_CHUNK)
        );
        Path file = writeFixture(dir, "full", snapshot);

        FlashbackValidator.Report report = FlashbackValidator.validateRenderable(file);
        assertTrue(report.valid(), "expected valid but got problems: " + report.problems());
        assertTrue(report.problems().isEmpty(), report.problems().toString());
    }

    // -----------------------------------------------------------------------
    // Negative fixture: configuration_packet missing
    // -----------------------------------------------------------------------

    @Test
    void rejectsMissingConfigurationPacket(@TempDir Path dir) throws Exception {
        List<ReplayAction> snapshot = List.of(
            // CONFIG_PACKET intentionally omitted
            gamePacket(ID_LOGIN),
            new ReplayAction(CREATE_LOCAL_PLAYER, new byte[32]),
            gamePacket(ID_POSITION),
            gamePacket(ID_PLAYER_INFO),
            gamePacket(ID_CHUNK)
        );
        Path file = writeFixture(dir, "no-config", snapshot);

        FlashbackValidator.Report report = FlashbackValidator.validateRenderable(file);
        assertFalse(report.valid(), "should be invalid when configuration_packet is absent");
        assertTrue(
            report.problems().stream().anyMatch(p -> p.toLowerCase().contains("configuration")),
            "problem should mention configuration data, got: " + report.problems()
        );
    }

    // -----------------------------------------------------------------------
    // Negative fixture: chunk packet (id=39) missing
    // -----------------------------------------------------------------------

    @Test
    void rejectsMissingChunkPacket(@TempDir Path dir) throws Exception {
        List<ReplayAction> snapshot = List.of(
            new ReplayAction(CONFIG_PACKET, new byte[]{0x07, 0x01}),
            gamePacket(ID_LOGIN),
            new ReplayAction(CREATE_LOCAL_PLAYER, new byte[32]),
            gamePacket(ID_POSITION),
            gamePacket(ID_PLAYER_INFO)
            // ID_CHUNK intentionally omitted
        );
        Path file = writeFixture(dir, "no-chunk", snapshot);

        FlashbackValidator.Report report = FlashbackValidator.validateRenderable(file);
        assertFalse(report.valid(), "should be invalid when chunk packet is absent");
        assertTrue(
            report.problems().stream().anyMatch(p -> p.contains("39") || p.toLowerCase().contains("chunk")),
            "problem should mention chunk/id=39, got: " + report.problems()
        );
    }

    // -----------------------------------------------------------------------
    // Negative fixture: login packet (id=43) missing
    // -----------------------------------------------------------------------

    @Test
    void rejectsMissingLoginPacket(@TempDir Path dir) throws Exception {
        List<ReplayAction> snapshot = List.of(
            new ReplayAction(CONFIG_PACKET, new byte[]{0x07, 0x01}),
            // ID_LOGIN intentionally omitted
            new ReplayAction(CREATE_LOCAL_PLAYER, new byte[32]),
            gamePacket(ID_POSITION),
            gamePacket(ID_PLAYER_INFO),
            gamePacket(ID_CHUNK)
        );
        Path file = writeFixture(dir, "no-login", snapshot);

        FlashbackValidator.Report report = FlashbackValidator.validateRenderable(file);
        assertFalse(report.valid(), "should be invalid when login packet is absent");
        assertTrue(
            report.problems().stream().anyMatch(p -> p.contains("43") || p.toLowerCase().contains("login")),
            "problem should mention login/id=43, got: " + report.problems()
        );
    }

    // -----------------------------------------------------------------------
    // Negative fixture: create_local_player action missing
    // -----------------------------------------------------------------------

    @Test
    void rejectsMissingCreateLocalPlayer(@TempDir Path dir) throws Exception {
        List<ReplayAction> snapshot = List.of(
            new ReplayAction(CONFIG_PACKET, new byte[]{0x07, 0x01}),
            gamePacket(ID_LOGIN),
            // CREATE_LOCAL_PLAYER intentionally omitted
            gamePacket(ID_POSITION),
            gamePacket(ID_PLAYER_INFO),
            gamePacket(ID_CHUNK)
        );
        Path file = writeFixture(dir, "no-clp", snapshot);

        FlashbackValidator.Report report = FlashbackValidator.validateRenderable(file);
        assertFalse(report.valid(), "should be invalid when create_local_player is absent");
        assertTrue(
            report.problems().stream().anyMatch(p -> p.contains("create_local_player")),
            "problem should mention create_local_player, got: " + report.problems()
        );
    }

    // -----------------------------------------------------------------------
    // validateRenderable still rejects structurally broken files
    // -----------------------------------------------------------------------

    @Test
    void rejectsTickMismatchViaStructuralCheck(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mismatch.flashback");
        var meta = new FlashbackMeta();
        meta.name = "mismatch";
        meta.totalTicks = 5; // wrong
        meta.chunks.put("c0.flashback", new ChunkMeta(5));

        List<ReplayAction> snapshot = List.of(
            new ReplayAction(CONFIG_PACKET, new byte[]{0x07, 0x01}),
            gamePacket(ID_LOGIN),
            new ReplayAction(CREATE_LOCAL_PLAYER, new byte[32]),
            gamePacket(ID_POSITION),
            gamePacket(ID_PLAYER_INFO),
            gamePacket(ID_CHUNK)
        );
        // Only 1 next_tick in stream but meta claims 5.
        byte[] chunk = ChunkWriter.write(snapshot, List.of(new ReplayAction(NEXT_TICK, new byte[0])));
        try (var w = FlashbackContainer.create(file)) {
            w.writeMetadata(meta);
            w.writeChunk("c0.flashback", chunk);
        }

        FlashbackValidator.Report report = FlashbackValidator.validateRenderable(file);
        assertFalse(report.valid(), "structurally broken file should still fail validateRenderable");
        assertTrue(
            report.problems().stream().anyMatch(p -> p.contains("tick")),
            "expected tick-mismatch problem, got: " + report.problems()
        );
    }
}
