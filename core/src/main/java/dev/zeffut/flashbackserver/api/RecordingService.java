package dev.zeffut.flashbackserver.api;

import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Public recording controls exposed by FlashbackServer.
 *
 * <p>Obtain via {@link FlashbackAPI#recording()} or Bukkit's ServicesManager:
 * {@code Bukkit.getServicesManager().load(RecordingService.class)}.
 *
 * <p><b>Threading:</b> methods may be called from any thread. {@link #stop(Player)} completes
 * its future on an <em>async</em> scheduler thread — do not touch Bukkit API in the callback
 * without hopping back to the player's region thread
 * ({@code player.getScheduler().run(plugin, task, retired)}).
 *
 * <p>This interface is a consumer contract. Do not implement it in your own plugin except for
 * test doubles — methods may be added in future releases.
 */
public interface RecordingService {

    /**
     * Starts a full recording for {@code player}.
     *
     * @return {@code true} if recording started; {@code false} if already recording
     */
    boolean start(Player player);

    /**
     * Stops the player's recording and writes the {@code .flashback} file asynchronously
     * to the default location ({@code plugins/FlashbackServer/replays/<name>-<uuid>.flashback}).
     *
     * <p>The returned future completes with the output path, or {@code null} if the player was not
     * being recorded. It completes exceptionally if the file could not be written.
     *
     * <p>Completion thread is async — see class-level threading notes.
     */
    CompletableFuture<Path> stop(Player player);

    /**
     * Stops the player's recording and writes the file asynchronously to {@code outputFile}.
     *
     * <p>Path rules:
     * <ul>
     *   <li>{@code null} — same as {@link #stop(Player)} (default location)</li>
     *   <li>absolute path — used as-is (parent directories are created)</li>
     *   <li>relative path — resolved against the FlashbackServer data folder
     *       ({@code plugins/FlashbackServer/})</li>
     * </ul>
     * The path should include the file name, e.g. {@code rounds/final.flashback} or
     * {@code Path.of("D:/replays/match.flashback")}.
     *
     * <p>Files written to custom paths are produced and validated through this API — call
     * {@link FlashbackAPI#verify(java.nio.file.Path)} on the returned path (or any path).
     * {@code /replay verify} only searches the default {@code replays/} and {@code clips/}
     * directories; it is not required to see custom-path outputs.
     *
     * <p>Same path is overwritten if it already exists. Parent directories are created.
     * The suffix is not enforced; prefer {@code .flashback}. If the path comes from an untrusted
     * source (command, config), the caller must validate it — this API does not restrict
     * {@code ..} or absolute paths.
     *
     * @return future completing with the resolved output path, or {@code null} if not recording
     * @throws java.util.concurrent.CompletionException on write failure (future completes exceptionally);
     *         completion thread is async — see class-level threading notes
     */
    CompletableFuture<Path> stop(Player player, Path outputFile);

    /** @return {@code true} if {@code player} is currently being recorded */
    boolean isRecording(Player player);
}
