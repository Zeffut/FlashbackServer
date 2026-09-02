package dev.zeffut.flashbackserver.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RefusedPacketsTest {

    private static final String GAME = "net.minecraft.network.protocol.game.";

    @Test
    void refusesTheTypesThatCrashPlayback() {
        // The three that were actually observed crashing this plugin's own recordings, in order.
        assertTrue(RefusedPackets.isRefused(GAME + "ClientboundPlayerPositionPacket"));
        assertTrue(RefusedPackets.isRefused(GAME + "ClientboundKeepAlivePacket"));
        assertTrue(RefusedPackets.isRefused("net.minecraft.network.protocol.BundleDelimiterPacket"));
    }

    @Test
    void refusesMoveEntitySubclasses() {
        // ClientboundMoveEntityPacket is abstract; only these three ever reach the wire, and they
        // were 1,118 of the 3,976 packets in the recording that would not open.
        assertTrue(RefusedPackets.isRefused(GAME + "ClientboundMoveEntityPacket$Pos"));
        assertTrue(RefusedPackets.isRefused(GAME + "ClientboundMoveEntityPacket$PosRot"));
        assertTrue(RefusedPackets.isRefused(GAME + "ClientboundMoveEntityPacket$Rot"));
    }

    @Test
    void anUnrelatedInnerClassNamedPosIsNotRefused() {
        // Guards the reason this matches binary names rather than getSimpleName(): the simple name
        // of ClientboundMoveEntityPacket$Pos is just "Pos".
        assertFalse(RefusedPackets.isRefused(GAME + "ClientboundSomethingElse$Pos"));
    }

    @Test
    void keepsThePacketsThatCarryTheBuild() {
        assertFalse(RefusedPackets.isRefused(GAME + "ClientboundBlockUpdatePacket"));
        assertFalse(RefusedPackets.isRefused(GAME + "ClientboundLevelChunkWithLightPacket"));
        assertFalse(RefusedPackets.isRefused(GAME + "ClientboundAddEntityPacket"));
        assertFalse(RefusedPackets.isRefused(GAME + "ClientboundTeleportEntityPacket"));
    }
}
