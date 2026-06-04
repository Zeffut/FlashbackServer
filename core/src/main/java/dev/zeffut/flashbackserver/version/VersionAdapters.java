package dev.zeffut.flashbackserver.version;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * Factory for obtaining the active {@link VersionAdapter}.
 *
 * <p>The concrete NMS adapter lives in a separate Gradle module ({@code :nms:v1_21_5}) which is
 * shaded into the final plugin jar at build time. {@code :core} therefore has no compile-time
 * dependency on the adapter — it is resolved <strong>reflectively</strong> here.
 *
 * <p>A future MV3c step will switch on {@code Bukkit.getMinecraftVersion()} to select the FQN of
 * the appropriate implementation. For now this resolves to the single v1_21_5 adapter, logging a
 * warning if the running Minecraft version differs.
 */
public final class VersionAdapters {
    private VersionAdapters() {}

    private static final Logger LOG = Logger.getLogger(VersionAdapters.class.getName());

    /** Fully-qualified class name of the v1_21_5 adapter (shaded in from {@code :nms:v1_21_5}). */
    private static final String V1_21_5_FQN =
        "dev.zeffut.flashbackserver.version.v1_21_5.V1_21_5Adapter";

    /** Minecraft version this build's bundled adapter targets. */
    private static final String SUPPORTED_VERSION = "1.21.5";

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

    /**
     * Resolves the adapter FQN for the running Minecraft version. Currently always returns the
     * v1_21_5 adapter, but logs a warning if the detected version differs (MV3c will add a real
     * version→FQN switch).
     */
    private static String resolveAdapterFqn() {
        String running;
        try {
            running = Bukkit.getMinecraftVersion();
        } catch (Throwable t) {
            // Bukkit not available (shouldn't happen at runtime) — fall back to the default.
            return V1_21_5_FQN;
        }
        if (running != null && !SUPPORTED_VERSION.equals(running)) {
            LOG.warning("FlashbackServer was built for Minecraft " + SUPPORTED_VERSION
                + " but the server is running " + running
                + ". Falling back to the " + SUPPORTED_VERSION + " adapter; behaviour may be incorrect.");
        }
        return V1_21_5_FQN;
    }
}
