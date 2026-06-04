package dev.zeffut.flashbackserver.version;

import org.bukkit.Bukkit;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Factory for obtaining the active {@link VersionAdapter}.
 *
 * <p>Each concrete NMS adapter lives in a separate Gradle module ({@code :nms:vX}) shaded into the
 * final plugin jar at build time. {@code :core} therefore has no compile-time dependency on the
 * adapters — they are resolved <strong>reflectively</strong> here.
 *
 * <p>The running Minecraft version ({@code Bukkit.getMinecraftVersion()}) selects the adapter FQN.
 * Add a row to {@link #ADAPTERS_BY_VERSION} (and a matching {@code :nms:vX} module + a
 * {@code PacketIds} row) per supported version. An unrecognised version falls back to the newest
 * known adapter with a warning.
 */
public final class VersionAdapters {
    private VersionAdapters() {}

    private static final Logger LOG = Logger.getLogger(VersionAdapters.class.getName());

    /** Minecraft version → adapter FQN (each shaded in from its {@code :nms:vX} module). */
    private static final Map<String, String> ADAPTERS_BY_VERSION = Map.of(
        "1.21.5",  "dev.zeffut.flashbackserver.version.v1_21_5.V1_21_5Adapter",
        "1.21.6",  "dev.zeffut.flashbackserver.version.v1_21_6.V1_21_6Adapter",
        "1.21.7",  "dev.zeffut.flashbackserver.version.v1_21_7.V1_21_7Adapter",
        "1.21.8",  "dev.zeffut.flashbackserver.version.v1_21_8.V1_21_8Adapter",
        "1.21.9",  "dev.zeffut.flashbackserver.version.v1_21_9.V1_21_9Adapter",
        "1.21.10", "dev.zeffut.flashbackserver.version.v1_21_10.V1_21_10Adapter",
        "1.21.11", "dev.zeffut.flashbackserver.version.v1_21_11.V1_21_11Adapter"
    );

    /** Adapter used when the running version isn't in the table (newest known). */
    private static final String FALLBACK_FQN =
        "dev.zeffut.flashbackserver.version.v1_21_11.V1_21_11Adapter";

    /** Returns the {@link VersionAdapter} for the running Minecraft version. */
    public static VersionAdapter current() {
        String fqn = resolveAdapterFqn();
        try {
            Class<?> clazz = Class.forName(fqn);
            return (VersionAdapter) clazz.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "Version adapter class not found: " + fqn
                    + ". The :nms adapter module was not shaded into the plugin jar.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to instantiate version adapter: " + fqn, e);
        }
    }

    private static String resolveAdapterFqn() {
        String running;
        try {
            running = Bukkit.getMinecraftVersion();
        } catch (Throwable t) {
            return FALLBACK_FQN; // Bukkit not available (shouldn't happen at runtime)
        }
        String fqn = ADAPTERS_BY_VERSION.get(running);
        if (fqn != null) return fqn;
        LOG.warning("FlashbackServer has no version adapter for Minecraft " + running
            + "; falling back to the newest known adapter. Recordings may be incorrect — "
            + "add an :nms module + a VersionAdapters row for this version.");
        return FALLBACK_FQN;
    }
}
