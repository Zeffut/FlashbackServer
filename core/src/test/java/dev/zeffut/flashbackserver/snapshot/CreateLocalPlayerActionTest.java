package dev.zeffut.flashbackserver.snapshot;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CreateLocalPlayerAction} — verifies the exact byte layout of both the
 * fixed-size prefix and the full payload (including GameProfile wire format) without requiring a
 * running server or NMS classes.
 *
 * <h2>Primitive-prefix layout</h2>
 * <pre>
 *   [ UUID msb 8B ][ UUID lsb 8B ]  → 16 bytes
 *   [ x 8B ][ y 8B ][ z 8B ]        → 24 bytes  (doubles, big-endian)
 *   [ xRot 4B ][ yRot 4B ][ yHeadRot 4B ] → 12 bytes  (floats, big-endian)
 *   [ vx 8B ][ vy 8B ][ vz 8B ]     → 24 bytes  (doubles, big-endian)
 * </pre>
 * Total: 76 bytes.
 */
class CreateLocalPlayerActionTest {

    private static final UUID TEST_UUID = new UUID(0x1234_5678_9ABC_DEF0L, 0xFEDC_BA98_7654_3210L);
    private static final double X = 12.5, Y = 64.0, Z = -3.75;
    private static final float X_ROT = 1.2f, Y_ROT = -0.5f, Y_HEAD_ROT = 1.8f;
    private static final double VX = 0.1, VY = -0.2, VZ = 0.3;

    // Profile used for full-payload tests
    private static final UUID PROFILE_ID = new UUID(0xAAAA_BBBB_CCCC_DDDdl, 0xEEEE_FFFF_0000_1111L);
    private static final String PROFILE_NAME = "TestPlayer";
    private static final String PROP_NAME = "textures";
    private static final String PROP_VALUE = "eyJ0ZXh0dXJlcyI6e319";  // fake base64
    private static final String PROP_SIG = "fakeSig";

    // ── primitivePrefix tests ───────────────────────────────────────────────

    @Test
    void prefixIs76Bytes() {
        byte[] bytes = prefix();
        assertEquals(76, bytes.length, "fixed prefix must be exactly 76 bytes");
    }

    @Test
    void uuidIsFirst16Bytes() {
        byte[] bytes = prefix();
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        long msb = bb.getLong(0);
        long lsb = bb.getLong(8);
        assertEquals(TEST_UUID.getMostSignificantBits(), msb, "UUID msb");
        assertEquals(TEST_UUID.getLeastSignificantBits(), lsb, "UUID lsb");
    }

    @Test
    void positionDoublesFollowUuid() {
        byte[] bytes = prefix();
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        assertEquals(X, bb.getDouble(16), 1e-10, "x at offset 16");
        assertEquals(Y, bb.getDouble(24), 1e-10, "y at offset 24");
        assertEquals(Z, bb.getDouble(32), 1e-10, "z at offset 32");
    }

    @Test
    void rotationFloatsFollowPosition() {
        byte[] bytes = prefix();
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        assertEquals(X_ROT,      bb.getFloat(40), 1e-5f, "xRot at offset 40");
        assertEquals(Y_ROT,      bb.getFloat(44), 1e-5f, "yRot at offset 44");
        assertEquals(Y_HEAD_ROT, bb.getFloat(48), 1e-5f, "yHeadRot at offset 48");
    }

    @Test
    void velocityDoublesFollowRotations() {
        byte[] bytes = prefix();
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        assertEquals(VX, bb.getDouble(52), 1e-10, "vx at offset 52");
        assertEquals(VY, bb.getDouble(60), 1e-10, "vy at offset 60");
        assertEquals(VZ, bb.getDouble(68), 1e-10, "vz at offset 68");
    }

    @Test
    void identifierConstant() {
        assertEquals("flashback:action/create_local_player", CreateLocalPlayerAction.IDENTIFIER);
    }

    // ── full payload (build) tests — GameProfile wire format ───────────────

    @Test
    void fullPayloadStartsWith76BytePrefix() {
        byte[] bytes = fullPayload();
        // The first 76 bytes must match the primitive prefix exactly
        byte[] expected = prefix();
        for (int i = 0; i < 76; i++) {
            assertEquals(expected[i], bytes[i],
                    "byte mismatch at offset " + i + " between full payload and primitivePrefix");
        }
    }

    @Test
    void gameProfileSectionContainsUuidNameAndProperty() throws Exception {
        byte[] bytes = fullPayload();
        // Skip the 76-byte fixed prefix to reach the GameProfile section
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes, 76, bytes.length - 76));

        // GameProfile UUID (2 longs)
        long profMsb = dis.readLong();
        long profLsb = dis.readLong();
        assertEquals(PROFILE_ID.getMostSignificantBits(), profMsb, "profile UUID msb");
        assertEquals(PROFILE_ID.getLeastSignificantBits(), profLsb, "profile UUID lsb");

        // Profile name (varint-prefixed UTF-8)
        String readName = readVarString(dis);
        assertEquals(PROFILE_NAME, readName, "profile name");

        // Property count
        int propCount = readVarInt(dis);
        assertEquals(1, propCount, "property count");

        // Property: name, value, optional signature
        String propName = readVarString(dis);
        assertEquals(PROP_NAME, propName, "property name");
        String propValue = readVarString(dis);
        assertEquals(PROP_VALUE, propValue, "property value");

        boolean hasSig = dis.readBoolean();
        assertTrue(hasSig, "signature present flag");
        String sig = readVarString(dis);
        assertEquals(PROP_SIG, sig, "property signature");

        // gameModeId (SURVIVAL = 0) — single byte varint
        int gameModeId = readVarInt(dis);
        assertEquals(0, gameModeId, "gameModeId SURVIVAL=0");
    }

    @Test
    void gameProfileSectionNoPropertiesNoSignature() throws Exception {
        byte[] bytes = CreateLocalPlayerAction.build(
                TEST_UUID, X, Y, Z, X_ROT, Y_ROT, Y_HEAD_ROT, VX, VY, VZ,
                PROFILE_ID, PROFILE_NAME,
                List.of(),  // no properties
                1 /* CREATIVE */);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes, 76, bytes.length - 76));

        // Skip GameProfile UUID + name (already tested above)
        dis.readLong(); dis.readLong();
        readVarString(dis);

        int propCount = readVarInt(dis);
        assertEquals(0, propCount, "no properties");

        int gameModeId = readVarInt(dis);
        assertEquals(1, gameModeId, "gameModeId CREATIVE=1");
    }

    @Test
    void gameProfilePropertyWithNullSignature() throws Exception {
        List<String[]> propsNoSig = new ArrayList<>();
        propsNoSig.add(new String[]{PROP_NAME, PROP_VALUE, null});
        byte[] bytes = CreateLocalPlayerAction.build(
                TEST_UUID, X, Y, Z, X_ROT, Y_ROT, Y_HEAD_ROT, VX, VY, VZ,
                PROFILE_ID, PROFILE_NAME,
                propsNoSig,
                2 /* ADVENTURE */);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes, 76, bytes.length - 76));
        dis.readLong(); dis.readLong(); // profile UUID
        readVarString(dis);             // name
        int propCount = readVarInt(dis);
        assertEquals(1, propCount);
        readVarString(dis); // prop name
        readVarString(dis); // prop value
        boolean hasSig = dis.readBoolean();
        assertFalse(hasSig, "null signature → false boolean");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static byte[] prefix() {
        return CreateLocalPlayerAction.primitivePrefix(
                TEST_UUID,
                X, Y, Z,
                X_ROT, Y_ROT, Y_HEAD_ROT,
                VX, VY, VZ);
    }

    private static byte[] fullPayload() {
        List<String[]> props = new ArrayList<>();
        props.add(new String[]{PROP_NAME, PROP_VALUE, PROP_SIG});
        return CreateLocalPlayerAction.build(
                TEST_UUID, X, Y, Z, X_ROT, Y_ROT, Y_HEAD_ROT, VX, VY, VZ,
                PROFILE_ID, PROFILE_NAME,
                props,
                0 /* SURVIVAL */);
    }

    /** Reads a varint-length-prefixed UTF-8 string (matches VarCodec.readString). */
    private static String readVarString(DataInputStream dis) throws Exception {
        int len = readVarInt(dis);
        byte[] buf = new byte[len];
        dis.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /** Reads an unsigned LEB128 VarInt (matches VarCodec.readVarInt). */
    private static int readVarInt(DataInputStream dis) throws Exception {
        int result = 0, shift = 0, b;
        do {
            b = dis.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }
}
