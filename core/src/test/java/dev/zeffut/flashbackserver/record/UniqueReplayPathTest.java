package dev.zeffut.flashbackserver.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class UniqueReplayPathTest {
    @TempDir Path dir;

    @Test void successiveDefaultsSurviveRestartAndExistingFiles() throws Exception {
        Path first = ReplayFiles.reserveUnique(dir, "Ada-uuid", ".flashback");
        Files.writeString(first, "first replay");
        // Simulate manager/process restart: no in-memory counter is carried forward.
        Path afterRestart = ReplayFiles.reserveUnique(dir, "Ada-uuid", ".flashback");
        assertNotEquals(first, afterRestart);
        assertEquals("first replay", Files.readString(first));
        assertTrue(Files.exists(afterRestart));
    }

    @Test void concurrentReservationsNeverCollideOrOverwrite() throws Exception {
        Set<Path> paths = ConcurrentHashMap.newKeySet();
        var pool = Executors.newFixedThreadPool(12);
        try {
            var futures = IntStream.range(0, 100).mapToObj(i -> pool.submit(() -> {
                Path path = ReplayFiles.reserveUnique(dir, "player-clip", ".flashback");
                Files.writeString(path, "owner-" + i);
                return path;
            })).toList();
            for (var future : futures) paths.add(future.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(100, paths.size());
        assertEquals(100, Files.list(dir).count());
        assertEquals(100, paths.stream().map(path -> {
            try { return Files.readString(path); } catch (Exception e) { throw new RuntimeException(e); }
        }).distinct().count());
    }
}
