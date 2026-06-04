package dev.zeffut.flashbackserver.platform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformSchedulerTest {
    @Test
    void hasExpectedApi() throws Exception {
        assertNotNull(PlatformScheduler.class.getMethod(
            "repeatForEntity", org.bukkit.plugin.Plugin.class, org.bukkit.entity.Entity.class,
            long.class, Runnable.class));
        assertNotNull(PlatformScheduler.class.getMethod(
            "async", org.bukkit.plugin.Plugin.class, Runnable.class));
    }
}
