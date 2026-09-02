package dev.zeffut.flashbackserver.version;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionAdaptersTest {

    @Test
    void selectsDedicatedAdapterForMinecraft262() throws ReflectiveOperationException {
        Field adapters = VersionAdapters.class.getDeclaredField("ADAPTERS_BY_VERSION");
        adapters.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> byVersion = (Map<String, String>) adapters.get(null);

        assertEquals(
                "dev.zeffut.flashbackserver.version.v26_2.V26_2Adapter",
                byVersion.get("26.2"));
    }
}
