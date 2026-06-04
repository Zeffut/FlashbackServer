package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlashbackMetaTest {
    @Test
    void serializesExpectedKeys() {
        var meta = new FlashbackMeta();
        meta.name = "test";
        meta.versionString = "1.21.4";
        meta.dataVersion = 4189;
        meta.protocolVersion = 769;
        meta.totalTicks = 40;
        meta.chunks.put("c0.flashback", new ChunkMeta(40));

        String json = meta.toJson();
        assertTrue(json.contains("\"version_string\""));
        assertTrue(json.contains("\"data_version\""));
        assertTrue(json.contains("\"protocol_version\""));
        assertTrue(json.contains("\"total_ticks\""));
        assertTrue(json.contains("\"chunks\""));
    }

    @Test
    void jsonRoundTrips() {
        var meta = new FlashbackMeta();
        meta.name = "round";
        meta.totalTicks = 100;
        meta.chunks.put("c0.flashback", new ChunkMeta(100));

        FlashbackMeta back = FlashbackMeta.fromJson(meta.toJson());
        assertEquals("round", back.name);
        assertEquals(100, back.totalTicks);
        assertEquals(100, back.chunks.get("c0.flashback").duration);
    }
}
