package dev.zeffut.flashbackserver.verify;

import dev.zeffut.flashbackserver.format.ChunkReader;
import dev.zeffut.flashbackserver.format.FlashbackContainer;
import dev.zeffut.flashbackserver.format.ReplayAction;
import dev.zeffut.flashbackserver.version.VersionAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * NMS-free core wrapper that opens a {@code .flashback} container, reads every chunk's actions,
 * and delegates decoding to the supplied {@link VersionAdapter}.
 *
 * <p>NMS access is entirely inside the adapter implementation — this class touches only our own
 * format types and Java standard library.
 */
public final class ReplayVerifier {

    private static final int MAX_PROBLEMS = 20;

    private ReplayVerifier() {}

    /** Aggregated result across all chunks of a single {@code .flashback} file. */
    public record Result(int decoded, int errors, List<String> problems) {}

    /**
     * Opens {@code file}, iterates every chunk, and decodes each
     * {@code game_packet} / {@code configuration_packet} action through {@code adapter}.
     *
     * @param file    path to a {@code .flashback} container
     * @param adapter the active version adapter
     * @return aggregated {@link Result} (errors == 0 means fully clean)
     */
    public static Result verify(Path file, VersionAdapter adapter) {
        int decoded = 0;
        int errors = 0;
        List<String> problems = new ArrayList<>();

        try (FlashbackContainer.Reader reader = FlashbackContainer.open(file)) {
            var meta = reader.readMetadata();

            for (String chunkName : meta.chunks.keySet()) {
                List<ReplayAction> snapshotActions;
                List<ReplayAction> streamActions;
                try {
                    ChunkReader.Result chunkResult = ChunkReader.read(reader.readChunk(chunkName));
                    snapshotActions = chunkResult.snapshotActions();
                    streamActions   = chunkResult.streamActions();
                } catch (Exception e) {
                    errors++;
                    if (problems.size() < MAX_PROBLEMS) {
                        problems.add("Failed to read chunk '" + chunkName + "': " + e.getMessage());
                    }
                    continue;
                }

                VersionAdapter.DecodeResult chunkResult =
                        adapter.decode(snapshotActions, streamActions);
                decoded += chunkResult.decoded();
                errors  += chunkResult.errors();
                for (String problem : chunkResult.problems()) {
                    if (problems.size() < MAX_PROBLEMS) {
                        problems.add("[chunk=" + chunkName + "] " + problem);
                    }
                }
            }
        } catch (Exception e) {
            errors++;
            if (problems.size() < MAX_PROBLEMS) {
                problems.add("Failed to open container '" + file + "': " + e.getMessage());
            }
        }

        return new Result(decoded, errors, List.copyOf(problems));
    }
}
