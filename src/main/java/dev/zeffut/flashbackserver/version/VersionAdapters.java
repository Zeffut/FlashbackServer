package dev.zeffut.flashbackserver.version;

import dev.zeffut.flashbackserver.version.v1_21_5.V1_21_5Adapter;

/**
 * Factory for obtaining the active {@link VersionAdapter}.
 *
 * <p>Currently returns a single {@link V1_21_5Adapter}. A future MV3c step will switch on
 * {@code Bukkit.getMinecraftVersion()} to return the appropriate implementation.
 */
public final class VersionAdapters {
    private VersionAdapters() {}

    /** Returns the {@link VersionAdapter} for the running Minecraft version. */
    public static VersionAdapter current() {
        return new V1_21_5Adapter();
    }
}
