package dev.zeffut.flashbackserver.capture;

/**
 * One recordable outbound packet: its binary class name and its encoded {@code varint id + payload}
 * bytes, ready to become a {@code flashback:action/game_packet}.
 *
 * <p>Packets the Flashback client refuses never reach a sink, and a bundle arrives as one
 * {@code CapturedPacket} per sub-packet — {@link PacketCapture} does both before fanning out.
 */
public record CapturedPacket(String packetClass, byte[] rawBytes) {}
