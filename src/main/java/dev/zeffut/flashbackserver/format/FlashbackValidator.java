package dev.zeffut.flashbackserver.format;

import java.nio.file.Path;
import java.util.*;

public final class FlashbackValidator {

    /**
     * Validation result. Fields other than {@code problems} are meaningful only when no
     * container-level failure occurred (on an unreadable container they default to 0).
     * {@code problems} is an unmodifiable list.
     */
    public record Report(boolean valid, List<String> problems, int totalTicks, int chunkCount) {}

    private FlashbackValidator() {}

    public static Report validate(Path file) {
        List<String> problems = new ArrayList<>();
        int totalTicks = 0;
        int chunkCount = 0;

        try (var reader = FlashbackContainer.open(file)) {
            Set<String> entries = reader.entryNames();
            if (!entries.contains("metadata.json")) {
                problems.add("missing metadata.json");
                return new Report(false, List.copyOf(problems), 0, 0);
            }

            FlashbackMeta meta = reader.readMetadata();
            totalTicks = meta.totalTicks;
            chunkCount = meta.chunks.size();

            long tickSum = 0;
            for (var entry : meta.chunks.entrySet()) {
                String name = entry.getKey();
                if (!entries.contains(name)) {
                    problems.add("declared chunk not present: " + name);
                    continue;
                }
                try {
                    ChunkReader.Result result = ChunkReader.read(reader.readChunk(name));
                    long ticks = result.streamActions().stream()
                        .filter(a -> a.identifier().equals("flashback:action/next_tick")).count();
                    if (ticks != entry.getValue().duration) {
                        problems.add("chunk " + name + " tick mismatch: declared "
                            + entry.getValue().duration + ", found " + ticks);
                    }
                    tickSum += ticks;
                } catch (Exception e) {
                    problems.add("chunk " + name + " unparseable: " + e.getMessage());
                }
            }
            if (tickSum != meta.totalTicks) {
                problems.add("total_ticks mismatch: declared " + meta.totalTicks + ", summed " + tickSum);
            }
        } catch (Exception e) {
            problems.add("container unreadable: " + e.getMessage());
        }

        return new Report(problems.isEmpty(), List.copyOf(problems), totalTicks, chunkCount);
    }
}
