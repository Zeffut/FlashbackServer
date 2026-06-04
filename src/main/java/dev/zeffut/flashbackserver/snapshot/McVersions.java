package dev.zeffut.flashbackserver.snapshot;

import net.minecraft.SharedConstants;

/**
 * Exposes live Minecraft protocol and data version numbers via {@link SharedConstants}.
 *
 * <p>All NMS access is confined to this package.
 */
public final class McVersions {

    private McVersions() {}

    /**
     * Returns the current protocol version (e.g. 770 for 1.21.5).
     *
     * @return the protocol version integer from {@link SharedConstants#getProtocolVersion()}
     */
    public static int protocolVersion() {
        return SharedConstants.getProtocolVersion();
    }

    /**
     * Returns the current data version (e.g. 4325 for 1.21.5).
     *
     * @return the data version integer from {@code SharedConstants.getCurrentVersion().getDataVersion().getVersion()}
     */
    public static int dataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }
}
