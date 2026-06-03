package dev.zeffut.flashbackserver.capture;

/**
 * An outbound packet observed on a player's channel. For P2a we record the packet's class name;
 * {@code rawBytes} stays null here (P2b will capture encoded id+payload bytes after the encoder).
 */
public record CapturedPacket(String packetClass, byte[] rawBytes) {}
