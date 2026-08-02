package dev.zeffut.flashbackserver.version;

import java.util.List;

/**
 * A per-player, version-specific view of one player's outbound packet stream.
 *
 * <p>Capture happens on the connection's Netty event loop <em>before</em> the encoder, so what
 * arrives is the live NMS packet object rather than bytes. That is deliberate:
 * {@link dev.zeffut.flashbackserver.capture.RefusedPackets} has to recognise packets by type, and
 * after the encoder there is nothing left to recognise but a per-version numeric id.
 *
 * <p>A translator is bound once per recording — building the PLAY codec means resolving the
 * player's registry access, which is far too expensive to redo for every packet on a live
 * connection.
 *
 * <p><strong>Threading:</strong> both methods are called on the player's Netty event loop, one
 * message at a time, so an implementation may keep unsynchronised per-instance state.
 */
public interface PacketTranslator {

    /**
     * Expands one outbound message into the packets that should be considered for recording.
     *
     * <p>Returns the sub-packets of a {@code ClientboundBundlePacket} in order, an empty list for a
     * bundle delimiter or anything that is not a PLAY packet at all, and a singleton list
     * otherwise. Bundles must be flattened rather than recorded whole: Flashback's replay client
     * asserts on the bundle delimiters (<em>"This packet should be handled by pipeline"</em>), and
     * its own recorder flattens them the same way.
     */
    List<Object> expand(Object message);

    /**
     * Encodes a clientbound PLAY packet to the {@code varint id + payload} bytes a
     * {@code flashback:action/game_packet} carries.
     *
     * @return the encoded bytes, or null if this packet cannot be encoded (which is logged and
     *     skipped rather than failing the recording — one unencodable packet should not cost the
     *     whole run)
     */
    byte[] encode(Object packet);
}
