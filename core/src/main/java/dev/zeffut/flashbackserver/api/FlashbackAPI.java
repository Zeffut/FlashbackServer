package dev.zeffut.flashbackserver.api;

import dev.zeffut.flashbackserver.format.FlashbackValidator;
import dev.zeffut.flashbackserver.verify.ReplayVerifier;
import dev.zeffut.flashbackserver.version.VersionAdapter;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Stable entry point for other plugins integrating with FlashbackServer.
 *
 * <p><b>Classloading:</b> this class lives in the FlashbackServer jar. If the plugin is
 * <em>not installed</em>, merely referencing {@link FlashbackAPI} throws
 * {@link NoClassDefFoundError} — {@link #isAvailable()} cannot protect you. Check installation
 * first:
 *
 * <pre>{@code
 * if (Bukkit.getPluginManager().getPlugin("FlashbackServer") == null) {
 *     return; // not installed — do not touch FlashbackAPI
 * }
 * if (!FlashbackAPI.isAvailable()) {
 *     return; // installed but not enabled
 * }
 * FlashbackAPI.recording().start(player);
 * }</pre>
 *
 * <p>Compile against the plugin jar with {@code compileOnly}. <b>Do not shade</b> the API
 * classes into your plugin: a second copy of {@code FlashbackAPI} is never bound and
 * {@link #isAvailable()} stays {@code false}.
 *
 * <p>Equivalent Bukkit-style lookup (same classloading caveat):
 * {@code Bukkit.getServicesManager().load(RecordingService.class)}.
 *
 * <p>Consumer plugins should declare {@code softdepend: [FlashbackServer]} so enable order and
 * class-loader visibility are correct.
 *
 * <p>Do not cache {@link #recording()} / {@link #clips()} across plugin (re)loads — after
 * FlashbackServer disables, a held reference is dead. Re-fetch on each use; the cost is a
 * volatile read.
 */
public final class FlashbackAPI {

    private static final String OWNER = "FlashbackServer";

    private static volatile RecordingService recording;
    private static volatile ClipService clips;

    private FlashbackAPI() {}

    /**
     * @return {@code true} when FlashbackServer is enabled and the API services are bound
     * @throws NoClassDefFoundError if the FlashbackServer jar is not installed (check
     *         {@code Bukkit.getPluginManager().getPlugin("FlashbackServer")} first)
     */
    public static boolean isAvailable() {
        return recording != null && clips != null;
    }

    /**
     * @return the recording service
     * @throws IllegalStateException if FlashbackServer is not enabled
     * @throws NoClassDefFoundError if the FlashbackServer jar is not installed
     */
    public static RecordingService recording() {
        RecordingService service = recording;
        if (service == null) {
            throw new IllegalStateException(
                    "FlashbackServer API is not available (plugin missing or not enabled)");
        }
        return service;
    }

    /**
     * @return the rolling-clip service
     * @throws IllegalStateException if FlashbackServer is not enabled
     * @throws NoClassDefFoundError if the FlashbackServer jar is not installed
     */
    public static ClipService clips() {
        ClipService service = clips;
        if (service == null) {
            throw new IllegalStateException(
                    "FlashbackServer API is not available (plugin missing or not enabled)");
        }
        return service;
    }

    /**
     * Validates a {@code .flashback} file produced by this plugin — including files written to
     * custom paths via {@link RecordingService#stop(Player, Path)} /
     * {@link ClipService#saveClip(org.bukkit.entity.Player, Path)}.
     *
     * <p>Layers:
     * <ol>
     *   <li><b>Format / container</b> ({@code FlashbackValidator}) — always runs.</li>
     *   <li><b>Packet decode</b> against the active server version adapter.
     *       Decode is <em>skipped</em> only when the adapter is unavailable (class not shaded /
     *       partial classpath — {@link VersionAdapters#isUnavailable}). In that case
     *       {@link ReplayCheckResult#decodeClean()} is {@code null} and format alone decides
     *       {@link ReplayCheckResult#valid()}. A {@code null} adapter, any other lookup failure,
     *       or any decoder failure (corrupt stream, linkage/programming error, unexpected
     *       exception) marks the result <b>invalid</b> ({@code decodeClean == false}).
     *       {@link VirtualMachineError} is rethrown (not swallowed).</li>
     * </ol>
     *
     * <p>Unlike {@code /replay verify}, this accepts <em>any</em> path, not only files under
     * the default {@code replays/} / {@code clips/} folders.
     *
     * <p>Does <b>not</b> require {@link #isAvailable()}; it only needs the FlashbackServer jar
     * so this class and format/verify internals load.
     *
     * @param file path to a {@code .flashback} container
     * @return check result
     * @throws IllegalArgumentException if {@code file} is {@code null}
     * @throws NoClassDefFoundError if the FlashbackServer jar is not installed
     */
    public static ReplayCheckResult verify(Path file) {
        return verify(file, VersionAdapters::current);
    }

    /**
     * Same as {@link #verify(Path)} but with an injectable adapter source (unit tests).
     * Package-private — not part of the public consumer contract.
     */
    static ReplayCheckResult verify(Path file, Supplier<VersionAdapter> adapterSupplier) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        FlashbackValidator.Report format = FlashbackValidator.validate(file);
        List<String> problems = new ArrayList<>(format.problems());
        int formatErrors = format.problems().size();
        int decodeErrors = 0;
        int decoded = 0;
        Boolean decodeClean = null;
        boolean decodeSkipped = false;

        VersionAdapter adapter;
        try {
            adapter = adapterSupplier.get();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            adapter = null;
            if (VersionAdapters.isUnavailable(t)) {
                decodeSkipped = true;
                decodeClean = null;
                problems.add("Packet decode skipped (version adapter unavailable): "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            } else {
                decodeClean = false;
                decodeErrors++;
                problems.add("Version adapter failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        if (adapter != null) {
            try {
                ReplayVerifier.Result decode = ReplayVerifier.verify(file, adapter);
                decodeClean = decode.errors() == 0;
                decoded = decode.decoded();
                decodeErrors += decode.errors();
                problems.addAll(decode.problems());
            } catch (Throwable t) {
                rethrowIfFatal(t);
                // Unexpected decoder failure must not look like a clean skip.
                decodeClean = false;
                decodeErrors++;
                problems.add("Packet decode failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        } else if (!decodeSkipped) {
            // Supplier returned null without throwing — not a supported skip path.
            decodeClean = false;
            decodeErrors++;
            problems.add("Version adapter supplier returned null");
        }

        boolean valid = format.valid() && (decodeSkipped || Boolean.TRUE.equals(decodeClean));
        return new ReplayCheckResult(
                valid,
                format.valid(),
                decodeClean,
                format.totalTicks(),
                format.chunkCount(),
                decoded,
                formatErrors + decodeErrors,
                problems);
    }

    /**
     * Fail-closed verification must not swallow fatal VM errors (OOM, stack overflow, etc.).
     * Other {@link Error}s are recorded as invalid results rather than rethrown.
     */
    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
    }

    /**
     * Only used for exceptions from adapter <em>lookup</em>. Decode failures always fail closed
     * and must not be classified as unavailable.
     *
     * @see VersionAdapters#isUnavailable(Throwable)
     */
    static boolean isAdapterUnavailable(Throwable t) {
        return VersionAdapters.isUnavailable(t);
    }

    /**
     * Binds the live services. Only the FlashbackServer plugin may call this.
     *
     * @param owner must be the FlashbackServer plugin instance
     */
    public static void bind(Plugin owner, RecordingService recordingService, ClipService clipService) {
        requireOwner(owner);
        recording = recordingService;
        clips = clipService;
    }

    /**
     * Clears the bound services. Only the FlashbackServer plugin may call this.
     *
     * @param owner must be the FlashbackServer plugin instance
     */
    public static void unbind(Plugin owner) {
        requireOwner(owner);
        recording = null;
        clips = null;
    }

    /** Package-private bind for unit tests in this package. */
    static void bind(RecordingService recordingService, ClipService clipService) {
        recording = recordingService;
        clips = clipService;
    }

    /** Package-private unbind for unit tests in this package. */
    static void unbind() {
        recording = null;
        clips = null;
    }

    private static void requireOwner(Plugin owner) {
        if (owner == null || !OWNER.equals(owner.getName())) {
            throw new IllegalArgumentException(
                    "FlashbackAPI.bind/unbind is internal to FlashbackServer");
        }
    }
}
