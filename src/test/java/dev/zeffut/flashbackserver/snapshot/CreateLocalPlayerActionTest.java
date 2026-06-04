package dev.zeffut.flashbackserver.snapshot;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CreateLocalPlayerAction#primitivePrefix} — verifies the exact field order
 * and byte encoding of the fixed-size prefix without requiring a running server or NMS classes.
 *
 * <p>The prefix layout is:
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

    // -------------------------------------------------------------------------

    private static byte[] prefix() {
        return CreateLocalPlayerAction.primitivePrefix(
                TEST_UUID,
                X, Y, Z,
                X_ROT, Y_ROT, Y_HEAD_ROT,
                VX, VY, VZ);
    }
}
