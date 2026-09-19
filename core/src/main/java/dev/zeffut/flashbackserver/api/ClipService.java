package dev.zeffut.flashbackserver.api;

import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Public rolling-clip controls exposed by FlashbackServer.
 *
 * <p>Obtain via {@link FlashbackAPI#clips()} or Bukkit's ServicesManager:
 * {@code Bukkit.getServicesManager().load(ClipService.class)}.
 *
 * <p><b>Threading:</b> methods may be called from any thread. {@link #saveClip(Player)} completes
 * its future on an <em>async</em> scheduler thread — do not touch Bukkit API in the callback
 * without hopping back to the player's region thread
 * ({@code player.getScheduler().run(plugin, task, retired)}).
 *
 * <p>This interface is a consumer contract. Do not implement it in your own plugin except for
 * test doubles — methods may be added in future releases.
 */
public interface ClipService {

    /**
     * Starts a rolling clip buffer for {@code player} (window length from plugin config).
     *
     * @return {@code true} if armed; {@code false} if already armed
     */
    boolean arm(Player player);

    /**
     * Stops and discards the player's rolling clip buffer.
     *
     * @return {@code true} if a buffer was disarmed; {@code false} if none was armed
     */
    boolean disarm(Player player);

    /** @return {@code true} if {@code player} currently has a rolling clip buffer */
    boolean isArmed(Player player);

    /**
     * Writes the player's current clip window to disk asynchronously, using the default location
     * ({@code plugins/FlashbackServer/clips/<name>-clip-N.flashback}).
     *
     * <p>The returned future completes with the output path, or {@code null} if the player is not
     * armed or the buffer has not finished its first snapshot yet. It completes exceptionally if
     * the file could not be written.
     *
     * <p>After {@link #arm(Player)}, wait at least one server tick before calling this — the first
     * keyframe snapshot is built on the player's region thread.
     *
     * <p>Completion thread is async — see class-level threading notes.
     */
    CompletableFuture<Path> saveClip(Player player);

    /**
     * Writes the player's current clip window to {@code outputFile} asynchronously.
     *
     * <p>Path rules:
     * <ul>
     *   <li>{@code null} — same as {@link #saveClip(Player)} (default location)</li>
     *   <li>absolute path — used as-is (parent directories are created)</li>
     *   <li>relative path — resolved against the FlashbackServer data folder
     *       ({@code plugin.getDataFolder()}, usually {@code plugins/FlashbackServer/})</li>
     * </ul>
     * Include the file name, e.g. {@code highlights/kill-01.flashback}.
     *
     * <p>Same readiness rules as {@link #saveClip(Player)} (wait ≥1 tick after {@link #arm(Player)}).
     *
     * <p>Custom-path files are API outputs — validate with {@link FlashbackAPI#verify(Path)}.
     * {@code /replay verify} only scans the default folders.
     *
     * <p>Same path is overwritten if it already exists. The suffix is not enforced; prefer
     * {@code .flashback}. If the path comes from an untrusted source (command, config), the
     * caller must validate it — this API does not restrict {@code ..} or absolute paths.
     *
     * @return future completing with the resolved output path, or {@code null} if not armed / not ready
     * @throws java.util.concurrent.CompletionException on write failure (future completes exceptionally);
     *         completion thread is async — see class-level threading notes
     */
    CompletableFuture<Path> saveClip(Player player, Path outputFile);
}
