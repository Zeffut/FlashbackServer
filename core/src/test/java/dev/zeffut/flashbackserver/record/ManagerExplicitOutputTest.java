package dev.zeffut.flashbackserver.record;

import dev.zeffut.flashbackserver.clip.ClipManager;
import dev.zeffut.flashbackserver.capture.PacketCapture;
import dev.zeffut.flashbackserver.platform.PlatformScheduler;
import dev.zeffut.flashbackserver.snapshot.SnapshotBuilder;
import dev.zeffut.flashbackserver.telemetry.Telemetry;
import dev.zeffut.flashbackserver.version.VersionAdapter;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real manager and ZIP writes; only Paper scheduling/capture and adapter are synthetic. No server/network. */
class ManagerExplicitOutputTest {
    @TempDir Path dir;

    private void exercise(Consumer<Harness> test) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("manager-fixture"));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(new UUID(0, 1));
        when(player.getName()).thenReturn("Fixture");
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        doAnswer(inv -> { ((Consumer<ScheduledTask>) inv.getArgument(1)).accept(null); return null; })
                .when(scheduler).run(eq(plugin), any(), isNull());
        VersionAdapter adapter = mock(VersionAdapter.class);
        when(adapter.protocolVersion()).thenReturn(769);
        when(adapter.dataVersion()).thenReturn(4189);
        Telemetry telemetry = mock(Telemetry.class); // capture suppressed, no ingest
        try (var capture = mockStatic(PacketCapture.class);
             var clocks = mockConstruction(TickClock.class);
             var snapshots = mockStatic(SnapshotBuilder.class);
             var adapters = mockStatic(VersionAdapters.class);
             var async = mockStatic(PlatformScheduler.class)) {
            snapshots.when(() -> SnapshotBuilder.configActions(player)).thenReturn(List.of());
            snapshots.when(() -> SnapshotBuilder.dynamicActions(player)).thenReturn(List.of());
            snapshots.when(() -> SnapshotBuilder.build(player)).thenReturn(List.of());
            adapters.when(VersionAdapters::current).thenReturn(adapter);
            async.when(() -> PlatformScheduler.async(eq(plugin), any())).thenAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run(); return null;
            });
            test.accept(new Harness(plugin, player, telemetry));
        }
    }
    record Harness(Plugin plugin, Player player, Telemetry telemetry) {}
    private void isZip(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) { assertTrue(zip.size() > 0); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private long count(Path path) {
        try (var files = Files.list(path)) { return files.count(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Test void recordingRelativeAndAbsoluteOverwriteAndReservationCleanup() throws Exception {
        exercise(h -> {
            Path recordings = dir.resolve("recordings");
            for (boolean absolute : List.of(false, true)) {
                Path target = dir.resolve(absolute ? "absolute.flashback" : "relative.flashback");
                Path requested = absolute ? target : Path.of("relative.flashback");
                for (int i=0; i<2; i++) {
                    try { Files.writeString(target, "old sentinel"); } catch (Exception e) { throw new AssertionError(e); }
                    var manager = new RecordingManager(h.plugin(), recordings, h.telemetry());
                    assertTrue(manager.start(h.player()));
                    assertEquals(1, count(recordings));
                    assertEquals(target, manager.stop(h.player(), requested).join());
                    isZip(target);
                    assertEquals(0, count(recordings), "explicit save must remove its default reservation");
                    assertFalse(manager.isRecording(h.player()));
                }
            }
        });
    }
    @Test void recordingSameFileAliasSurvivesCleanup() throws Exception {
        exercise(h -> {
            Path recordings = dir.resolve("recordings");
            var manager = new RecordingManager(h.plugin(), recordings, h.telemetry());
            assertTrue(manager.start(h.player()));
            Path reserved;
            try (var files = Files.list(recordings)) { reserved = files.findFirst().orElseThrow(); }
            catch (Exception e) { throw new AssertionError(e); }
            Path alias = dir.resolve("alias.flashback");
            try { Files.createLink(alias, reserved); } catch (Exception e) { throw new AssertionError(e); }
            assertEquals(alias, manager.stop(h.player(), alias).join());
            assertTrue(Files.exists(reserved), "same-file alias must not remove reserved output");
            isZip(alias); isZip(reserved);
        });
    }
    @Test void recordingFailedExplicitWriteCleansReservationNotTarget() throws Exception {
        exercise(h -> {
            Path recordings = dir.resolve("recordings");
            var manager = new RecordingManager(h.plugin(), recordings, h.telemetry());
            assertTrue(manager.start(h.player()));
            Path target = dir.resolve("existing-directory");
            try { Files.createDirectory(target); } catch (Exception e) { throw new AssertionError(e); }
            assertThrows(java.util.concurrent.CompletionException.class, () -> manager.stop(h.player(), target).join());
            assertTrue(Files.isDirectory(target));
            assertEquals(0, count(recordings));
        });
    }
    @Test void clipRelativeAndAbsoluteOverwriteWithoutDefaultFiles() throws Exception {
        exercise(h -> {
            Path clips = dir.resolve("clips");
            for (boolean absolute : List.of(false, true)) {
                var manager = new ClipManager(h.plugin(), clips, 30, h.telemetry());
                assertTrue(manager.arm(h.player()));
                Path target = dir.resolve(absolute ? "absolute-clip.flashback" : "relative-clip.flashback");
                Path requested = absolute ? target : Path.of("relative-clip.flashback");
                for (int i=0; i<2; i++) {
                    try { Files.writeString(target, "old sentinel"); } catch (Exception e) { throw new AssertionError(e); }
                    assertEquals(target, manager.saveClip(h.player(), requested).join());
                    isZip(target);
                    assertFalse(Files.exists(clips), "explicit clips must not reserve a default name");
                    assertTrue(manager.isArmed(h.player()));
                }
                assertTrue(manager.disarm(h.player()));
            }
        });
    }
}
