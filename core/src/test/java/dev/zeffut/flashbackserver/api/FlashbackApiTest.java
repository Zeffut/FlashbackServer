package dev.zeffut.flashbackserver.api;

import dev.zeffut.flashbackserver.record.FlashbackRecorder;
import dev.zeffut.flashbackserver.version.VersionAdapter;
import dev.zeffut.flashbackserver.version.VersionAdapterUnavailableException;
import dev.zeffut.flashbackserver.version.VersionAdapters;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashbackApiTest {

    @AfterEach
    void tearDown() {
        FlashbackAPI.unbind();
    }

    @Test
    void unavailableBeforeBind() {
        assertFalse(FlashbackAPI.isAvailable());
        assertThrows(IllegalStateException.class, FlashbackAPI::recording);
        assertThrows(IllegalStateException.class, FlashbackAPI::clips);
    }

    @Test
    void bindExposesServices() {
        RecordingService recording = new StubRecording();
        ClipService clips = new StubClips();
        FlashbackAPI.bind(recording, clips);

        assertTrue(FlashbackAPI.isAvailable());
        assertSame(recording, FlashbackAPI.recording());
        assertSame(clips, FlashbackAPI.clips());
    }

    @Test
    void unbindClearsServices() {
        FlashbackAPI.bind(new StubRecording(), new StubClips());
        FlashbackAPI.unbind();

        assertFalse(FlashbackAPI.isAvailable());
        assertThrows(IllegalStateException.class, FlashbackAPI::recording);
        assertThrows(IllegalStateException.class, FlashbackAPI::clips);
    }

    @Test
    void publicBindRejectsForeignOwner() {
        FlashbackAPI.bind(new StubRecording(), new StubClips());
        assertTrue(FlashbackAPI.isAvailable());

        Plugin foreign = pluginNamed("SomeOtherPlugin");
        assertThrows(IllegalArgumentException.class,
                () -> FlashbackAPI.bind(foreign, new StubRecording(), new StubClips()));
        assertThrows(IllegalArgumentException.class,
                () -> FlashbackAPI.unbind(foreign));
        assertThrows(IllegalArgumentException.class,
                () -> FlashbackAPI.bind(null, new StubRecording(), new StubClips()));
        assertTrue(FlashbackAPI.isAvailable());
    }

    @Test
    void verifyNullFileRejected() {
        assertThrows(IllegalArgumentException.class, () -> FlashbackAPI.verify(null));
    }

    @Test
    void verifyMissingFileIsInvalidNotThrow(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("absent.flashback");
        ReplayCheckResult result = FlashbackAPI.verify(missing);
        assertFalse(result.valid());
        assertTrue(result.errorCount() > 0);
        assertFalse(result.problems().isEmpty());
    }

    @Test
    void adapterUnavailableSkipsDecodeWithoutInvalidatingFormat(@TempDir Path dir) throws Exception {
        Path file = writeFormatValidReplay(dir);
        ReplayCheckResult result = FlashbackAPI.verify(file, () -> {
            throw new VersionAdapterUnavailableException(
                    "Version adapter class not found: x. The :nms adapter module was not shaded into the plugin jar.",
                    new ClassNotFoundException("x"));
        });
        assertTrue(result.formatValid());
        assertNull(result.decodeClean(), "known adapter-unavailable must skip decode, not fail it");
        assertTrue(result.valid(), "format-valid file must stay valid when decode is skipped: "
                + result.problems());
    }

    @Test
    void unexpectedDecoderFailureMakesResultInvalid(@TempDir Path dir) throws Exception {
        Path file = writeFormatValidReplay(dir);
        VersionAdapter exploding = adapterWithDecode(
                () -> { throw new IllegalStateException("corrupt packet stream"); });
        ReplayCheckResult result = FlashbackAPI.verify(file, () -> exploding);

        assertEquals(Boolean.FALSE, result.decodeClean(),
                "unexpected decoder failure must not be treated as a skip");
        assertFalse(result.valid(), "unexpected decoder failure must invalidate the result");
        assertTrue(result.errorCount() > 0);
        assertTrue(result.problems().stream().anyMatch(p ->
                        p.contains("corrupt packet stream") || p.contains("Packet decode failed")),
                result.problems().toString());
    }

    @Test
    void decoderErrorEscapingVerifierIsInvalidNotSkipped(@TempDir Path dir) throws Exception {
        Path file = writeFormatValidReplay(dir);
        VersionAdapter linkage = adapterWithDecode(() -> {
            throw new LinkageError("nms codec missing");
        });
        ReplayCheckResult result = FlashbackAPI.verify(file, () -> linkage);

        assertEquals(Boolean.FALSE, result.decodeClean());
        assertFalse(result.valid());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("Packet decode failed")),
                result.problems().toString());
        assertTrue(result.problems().stream().noneMatch(p -> p.contains("decode skipped")),
                result.problems().toString());
    }

    @Test
    void nonAdapterLookupFailureIsNotSkipped(@TempDir Path dir) throws Exception {
        Path file = writeFormatValidReplay(dir);
        ReplayCheckResult result = FlashbackAPI.verify(file, () -> {
            throw new IllegalStateException("Failed to instantiate version adapter: broken");
        });
        assertEquals(Boolean.FALSE, result.decodeClean());
        assertFalse(result.valid());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("Version adapter failed")),
                result.problems().toString());
    }

    @Test
    void nullAdapterSupplierIsInvalidNotSkipped(@TempDir Path dir) throws Exception {
        Path file = writeFormatValidReplay(dir);
        ReplayCheckResult result = FlashbackAPI.verify(file, () -> null);
        assertEquals(Boolean.FALSE, result.decodeClean());
        assertFalse(result.valid());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("returned null")),
                result.problems().toString());
    }

    @Test
    void cleanDecodePasses(@TempDir Path dir) throws Exception {
        Path file = writeFormatValidReplay(dir);
        VersionAdapter clean = adapterWithDecode(
                () -> new VersionAdapter.DecodeResult(3, 0, List.of()));
        ReplayCheckResult result = FlashbackAPI.verify(file, () -> clean);
        assertTrue(result.valid(), result.problems().toString());
        assertEquals(Boolean.TRUE, result.decodeClean());
        assertEquals(3, result.decodedPackets());
        assertEquals(0, result.errorCount());
    }

    @Test
    void adapterUnavailableClassification() {
        assertTrue(VersionAdapters.isUnavailable(new ClassNotFoundException("x")));
        assertTrue(VersionAdapters.isUnavailable(new NoClassDefFoundError("x")));
        assertTrue(VersionAdapters.isUnavailable(new VersionAdapterUnavailableException(
                "Version adapter class not found: y", new ClassNotFoundException("y"))));
        assertTrue(FlashbackAPI.isAdapterUnavailable(new VersionAdapterUnavailableException(
                "adapter missing", new ClassNotFoundException("y"))));
        assertFalse(VersionAdapters.isUnavailable(new IllegalStateException("corrupt packet stream")));
        assertFalse(VersionAdapters.isUnavailable(new IllegalStateException(
                "Failed to instantiate version adapter: broken")));
        assertFalse(VersionAdapters.isUnavailable(new RuntimeException("boom")));
        assertFalse(FlashbackAPI.isAdapterUnavailable(new IllegalStateException("corrupt packet stream")));
    }

    private static Path writeFormatValidReplay(Path dir) throws Exception {
        Path out = dir.resolve("check.flashback");
        FlashbackRecorder recorder = new FlashbackRecorder(out, "T", 769, 4189);
        recorder.onPacket(new byte[]{1});
        recorder.onTick();
        recorder.stop();
        return out;
    }

    private static VersionAdapter adapterWithDecode(
            java.util.function.Supplier<VersionAdapter.DecodeResult> decode) {
        return (VersionAdapter) Proxy.newProxyInstance(
                VersionAdapter.class.getClassLoader(),
                new Class<?>[]{VersionAdapter.class},
                (proxy, method, args) -> {
                    if ("decode".equals(method.getName())) return decode.get();
                    if ("toString".equals(method.getName())) return "StubAdapter";
                    return null;
                });
    }

    private static Plugin pluginNamed(String name) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return name;
                    if ("toString".equals(method.getName())) return "StubPlugin[" + name + "]";
                    if ("hashCode".equals(method.getName())) return name.hashCode();
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                });
    }

    private static final class StubRecording implements RecordingService {
        @Override public boolean start(Player player) { return true; }
        @Override public CompletableFuture<Path> stop(Player player) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Path> stop(Player player, Path outputFile) { return CompletableFuture.completedFuture(outputFile); }
        @Override public boolean isRecording(Player player) { return false; }
    }

    private static final class StubClips implements ClipService {
        @Override public boolean arm(Player player) { return true; }
        @Override public boolean disarm(Player player) { return true; }
        @Override public boolean isArmed(Player player) { return false; }
        @Override public CompletableFuture<Path> saveClip(Player player) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Path> saveClip(Player player, Path outputFile) { return CompletableFuture.completedFuture(outputFile); }
    }
}
