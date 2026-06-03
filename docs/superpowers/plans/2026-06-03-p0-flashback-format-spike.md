# Phase 0 — Flashback Format Spike (clean-room) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone, server-independent Java module that writes and reads
the Flashback replay container format (treating packet payloads as opaque bytes),
plus a structural validator that serves as the autonomous test oracle for all later
phases.

**Architecture:** A plain-Java `format` package inside a Paper-plugin Gradle project.
The container codec knows nothing about Minecraft packets — it serializes a sequence
of `(phase, actionId, payload bytes)` entries and a snapshot blob into the Flashback
binary chunk layout, packages chunks + `metadata.json` + `icon.png` into the
`.flashback` ZIP container, and reads it all back. Round-trip tests prove internal
consistency; a documented format spec (derived clean-room from the public Flashback
source) drives the interop-critical constants.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), paperweight-userdev (Paper 1.21.x),
Gson (metadata JSON), `java.util.zip` (container), JUnit 5 (tests). No ProtocolLib.
No code copied from Flashback — format learned by reading, implemented originally.

---

## Clean-room reminder

The Flashback source (Moulberry, "all rights reserved") may be **read** to learn the
file format, but **no code may be copied** and no class architecture reproduced. The
deliverable `docs/format/flashback-format.md` documents the *format*, not their code.
All implementation in this plan is original.

## File structure (created by this plan)

- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` — Gradle project
- `gradle/wrapper/…` — Gradle wrapper
- `src/main/resources/plugin.yml` — Paper plugin descriptor (minimal, for later phases)
- `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java` — empty bootstrap
- `src/main/java/dev/zeffut/flashbackserver/format/VarCodec.java` — VarInt/VarString
- `src/main/java/dev/zeffut/flashbackserver/format/FlashbackMeta.java` — metadata POJO + JSON
- `src/main/java/dev/zeffut/flashbackserver/format/ChunkMeta.java` — per-chunk metadata POJO
- `src/main/java/dev/zeffut/flashbackserver/format/ReplayAction.java` — `(phase, actionId, byte[] payload)` record
- `src/main/java/dev/zeffut/flashbackserver/format/ChunkWriter.java` — writes one `cN.flashback`
- `src/main/java/dev/zeffut/flashbackserver/format/ChunkReader.java` — reads one `cN.flashback`
- `src/main/java/dev/zeffut/flashbackserver/format/FlashbackContainer.java` — ZIP packaging
- `src/main/java/dev/zeffut/flashbackserver/format/FlashbackValidator.java` — structural oracle
- `src/test/java/dev/zeffut/flashbackserver/format/*Test.java` — JUnit tests
- `docs/format/flashback-format.md` — the documented format spec

---

### Task 1: Format investigation → `docs/format/flashback-format.md`

Read the public Flashback source to confirm the interop-critical constants, then write
the format spec. This is the clean-room documentation step; it produces a doc, no code.

**Files:**
- Create: `docs/format/flashback-format.md`

- [ ] **Step 1: Read the magic constant and core classes**

Fetch and read these raw files (read-only, to document the format):

```
https://raw.githubusercontent.com/Moulberry/Flashback/master/src/main/java/com/moulberry/flashback/Flashback.java
https://raw.githubusercontent.com/Moulberry/Flashback/master/src/main/java/com/moulberry/flashback/io/ReplayWriter.java
https://raw.githubusercontent.com/Moulberry/Flashback/master/src/main/java/com/moulberry/flashback/io/ReplayReader.java
https://raw.githubusercontent.com/Moulberry/Flashback/master/src/main/java/com/moulberry/flashback/io/AsyncReplaySaver.java
https://raw.githubusercontent.com/Moulberry/Flashback/master/src/main/java/com/moulberry/flashback/record/FlashbackMeta.java
```

Confirm and note down:
- the exact `MAGIC` int value (from `Flashback.java`),
- whether the `.flashback` container is a ZIP (inspect `AsyncReplaySaver` finalization),
- whether chunk files use any compression (zstd/gzip) — record the answer,
- the JSON key names in `FlashbackMeta.toJson()`,
- the action-registry encoding and the snapshot-block layout in `ReplayWriter`.

- [ ] **Step 2: Write the format spec doc**

Create `docs/format/flashback-format.md` documenting, in our own words:

```markdown
# Flashback file format (documented for interoperability)

> Learned by reading the public Flashback source. No code was copied.
> Verified against Flashback master on 2026-06-03.

## Container
- Extension: `.flashback`
- Packaging: ZIP archive  <!-- CONFIRM in Step 1 -->
- Entries:
  - `metadata.json`        — UTF-8 JSON, see below
  - `icon.png`             — 64×64 thumbnail (optional for our writer)
  - `c0.flashback`, `c1.flashback`, … — binary chunk streams
  - `level_chunk_caches/`  — optional, not produced by our writer

## Chunk binary layout (`cN.flashback`)
1. `int32 magic`           — value: <MAGIC from Step 1>
2. Action registry:
   - `varint count`
   - repeated `count` times: `varint index` + `string identifier`
3. Snapshot block:
   - `int32 size`
   - `size` bytes of snapshot data (sequence of actions, opaque to container)
4. Action stream, repeated until EOF:
   - `varint actionId`     — index into the registry
   - `int32 size`
   - `size` bytes payload  — opaque to the container codec
   The `flashback:action/next_tick` action (zero-length payload) advances one tick.

## Timing
- Tick-based, 20 ticks/second. No millisecond timestamps.
- A chunk spans up to 6000 ticks (300 s), then a new chunk starts.

## metadata.json keys
- `uuid` (string), `name` (string), `version_string` (string),
  `world_name` (string), `data_version` (int), `protocol_version` (int),
  `total_ticks` (int), `markers` (object), `chunks` (object of name→{duration:int})
  <!-- CONFIRM exact key names in Step 1 -->
```

Fill `<MAGIC from Step 1>` and resolve the `CONFIRM` notes with the real values found.

- [ ] **Step 3: Commit**

```bash
git add docs/format/flashback-format.md
git commit -m "docs: document Flashback container format (clean-room)"
```

---

### Task 2: Gradle Paper-plugin project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: `src/main/resources/plugin.yml`
- Create: `src/main/java/dev/zeffut/flashbackserver/FlashbackServerPlugin.java`

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "FlashbackServer"
```

- [ ] **Step 2: Write `gradle.properties`**

```properties
group=dev.zeffut
version=0.1.0-SNAPSHOT
```

- [ ] **Step 3: Write `build.gradle.kts`**

```kotlin
plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.7.7"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Write `src/main/resources/plugin.yml`**

```yaml
name: FlashbackServer
version: '0.1.0-SNAPSHOT'
main: dev.zeffut.flashbackserver.FlashbackServerPlugin
api-version: '1.21'
authors: [Zeffut]
description: Server-side Flashback replay recording.
```

- [ ] **Step 5: Write the empty bootstrap plugin class**

```java
package dev.zeffut.flashbackserver;

import org.bukkit.plugin.java.JavaPlugin;

public final class FlashbackServerPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("FlashbackServer enabled.");
    }
}
```

- [ ] **Step 6: Generate the Gradle wrapper and verify the build**

Run:
```bash
gradle wrapper --gradle-version 8.10 && ./gradlew build
```
Expected: `BUILD SUCCESSFUL`. (First run downloads the Paper dev bundle; allow time.)

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/ src/
git commit -m "chore: scaffold Paper plugin project with paperweight + gradle wrapper"
```

---

### Task 3: VarInt / VarString codec

Minecraft-style LEB128 VarInt and length-prefixed UTF-8 strings, used by the action
registry. Pure, no Minecraft classes.

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/format/VarCodec.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/format/VarCodecTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class VarCodecTest {
    @Test
    void varIntRoundTrips() throws IOException {
        for (int value : new int[]{0, 1, 127, 128, 255, 300, 2097151, Integer.MAX_VALUE}) {
            var out = new ByteArrayOutputStream();
            VarCodec.writeVarInt(new DataOutputStream(out), value);
            var in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
            assertEquals(value, VarCodec.readVarInt(in), "varint " + value);
        }
    }

    @Test
    void stringRoundTrips() throws IOException {
        var out = new ByteArrayOutputStream();
        VarCodec.writeString(new DataOutputStream(out), "flashback:action/next_tick");
        var in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("flashback:action/next_tick", VarCodec.readString(in));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*VarCodecTest'`
Expected: FAIL — `VarCodec` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class VarCodec {
    private VarCodec() {}

    public static void writeVarInt(DataOutput out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int readVarInt(DataInput in) throws IOException {
        int result = 0, shift = 0, b;
        do {
            b = in.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return result;
    }

    public static void writeString(DataOutput out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInput in) throws IOException {
        int len = readVarInt(in);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*VarCodecTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/zeffut/flashbackserver/format/VarCodec.java \
        src/test/java/dev/zeffut/flashbackserver/format/VarCodecTest.java
git commit -m "feat: add VarInt/VarString codec for Flashback container"
```

---

### Task 4: `FlashbackMeta` + `ChunkMeta` metadata model with JSON

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/format/ChunkMeta.java`
- Create: `src/main/java/dev/zeffut/flashbackserver/format/FlashbackMeta.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/format/FlashbackMetaTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FlashbackMetaTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write `ChunkMeta`**

```java
package dev.zeffut.flashbackserver.format;

public final class ChunkMeta {
    public int duration; // in ticks

    public ChunkMeta() {}
    public ChunkMeta(int duration) { this.duration = duration; }
}
```

- [ ] **Step 4: Write `FlashbackMeta`**

Field names mapped to the documented JSON keys via `@SerializedName`. Confirm the key
names against `docs/format/flashback-format.md` (Task 1).

```java
package dev.zeffut.flashbackserver.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class FlashbackMeta {
    private static final Gson GSON = new GsonBuilder().create();

    @SerializedName("uuid") public String uuid = UUID.randomUUID().toString();
    @SerializedName("name") public String name = "";
    @SerializedName("version_string") public String versionString = "";
    @SerializedName("world_name") public String worldName = "";
    @SerializedName("data_version") public int dataVersion;
    @SerializedName("protocol_version") public int protocolVersion;
    @SerializedName("total_ticks") public int totalTicks;
    @SerializedName("chunks") public Map<String, ChunkMeta> chunks = new LinkedHashMap<>();

    public String toJson() { return GSON.toJson(this); }
    public static FlashbackMeta fromJson(String json) { return GSON.fromJson(json, FlashbackMeta.class); }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests '*FlashbackMetaTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/zeffut/flashbackserver/format/ChunkMeta.java \
        src/main/java/dev/zeffut/flashbackserver/format/FlashbackMeta.java \
        src/test/java/dev/zeffut/flashbackserver/format/FlashbackMetaTest.java
git commit -m "feat: add Flashback metadata model with JSON round-trip"
```

---

### Task 5: `ReplayAction` + `ChunkWriter`/`ChunkReader` (binary chunk codec)

Serializes the chunk binary layout from Task 1, with packet payloads opaque.

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/format/ReplayAction.java`
- Create: `src/main/java/dev/zeffut/flashbackserver/format/ChunkWriter.java`
- Create: `src/main/java/dev/zeffut/flashbackserver/format/ChunkReader.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/format/ChunkCodecTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChunkCodecTest {
    @Test
    void chunkRoundTrips() throws Exception {
        byte[] snapshot = new byte[]{1, 2, 3, 4};
        var actions = List.of(
            new ReplayAction("flashback:game_packet", new byte[]{10, 20}),
            new ReplayAction("flashback:action/next_tick", new byte[0]),
            new ReplayAction("flashback:game_packet", new byte[]{30})
        );

        byte[] bytes = ChunkWriter.write(snapshot, actions);
        ChunkReader.Result result = ChunkReader.read(bytes);

        assertArrayEquals(snapshot, result.snapshot());
        assertEquals(actions.size(), result.actions().size());
        assertEquals("flashback:action/next_tick", result.actions().get(1).identifier());
        assertArrayEquals(new byte[]{30}, result.actions().get(2).payload());
    }

    @Test
    void rejectsBadMagic() {
        byte[] garbage = new byte[]{0, 0, 0, 0, 0};
        assertThrows(Exception.class, () -> ChunkReader.read(garbage));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ChunkCodecTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write `ReplayAction`**

```java
package dev.zeffut.flashbackserver.format;

public record ReplayAction(String identifier, byte[] payload) {}
```

- [ ] **Step 4: Write `ChunkWriter`**

`MAGIC` must equal the value documented in Task 1. The registry is built from the
distinct action identifiers in order of first appearance.

```java
package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.util.*;

public final class ChunkWriter {
    // Confirmed in Task 1 (docs/format/flashback-format.md) from Flashback.java — the real
    // MAGIC so files are interop-compatible with the Flashback client.
    static final int MAGIC = 0xD780_E884;

    private ChunkWriter() {}

    public static byte[] write(byte[] snapshot, List<ReplayAction> actions) throws IOException {
        LinkedHashMap<String, Integer> registry = new LinkedHashMap<>();
        for (ReplayAction a : actions) registry.computeIfAbsent(a.identifier(), k -> registry.size());

        var out = new ByteArrayOutputStream();
        var dos = new DataOutputStream(out);

        dos.writeInt(MAGIC);

        VarCodec.writeVarInt(dos, registry.size());
        for (Map.Entry<String, Integer> e : registry.entrySet()) {
            VarCodec.writeVarInt(dos, e.getValue());
            VarCodec.writeString(dos, e.getKey());
        }

        dos.writeInt(snapshot.length);
        dos.write(snapshot);

        for (ReplayAction a : actions) {
            VarCodec.writeVarInt(dos, registry.get(a.identifier()));
            dos.writeInt(a.payload().length);
            dos.write(a.payload());
        }
        dos.flush();
        return out.toByteArray();
    }
}
```

- [ ] **Step 5: Write `ChunkReader`**

```java
package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.util.*;

public final class ChunkReader {
    public record Result(byte[] snapshot, List<ReplayAction> actions) {}

    private ChunkReader() {}

    public static Result read(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));

        int magic = in.readInt();
        if (magic != ChunkWriter.MAGIC) throw new IOException("Bad magic: " + Integer.toHexString(magic));

        int count = VarCodec.readVarInt(in);
        Map<Integer, String> registry = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int index = VarCodec.readVarInt(in);
            registry.put(index, VarCodec.readString(in));
        }

        int snapshotSize = in.readInt();
        byte[] snapshot = new byte[snapshotSize];
        in.readFully(snapshot);

        List<ReplayAction> actions = new ArrayList<>();
        while (in.available() > 0) {
            int id = VarCodec.readVarInt(in);
            int size = in.readInt();
            byte[] payload = new byte[size];
            in.readFully(payload);
            String identifier = registry.get(id);
            if (identifier == null) throw new IOException("Unknown action id: " + id);
            actions.add(new ReplayAction(identifier, payload));
        }
        return new Result(snapshot, actions);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests '*ChunkCodecTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/zeffut/flashbackserver/format/ReplayAction.java \
        src/main/java/dev/zeffut/flashbackserver/format/ChunkWriter.java \
        src/main/java/dev/zeffut/flashbackserver/format/ChunkReader.java \
        src/test/java/dev/zeffut/flashbackserver/format/ChunkCodecTest.java
git commit -m "feat: add Flashback chunk binary codec (opaque payloads)"
```

---

### Task 6: `FlashbackContainer` — ZIP packaging

Packages `metadata.json` + chunk files into a `.flashback` ZIP, and reads them back.

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/format/FlashbackContainer.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/format/FlashbackContainerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlashbackContainerTest {
    @Test
    void containerRoundTrips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("test.flashback");

        var meta = new FlashbackMeta();
        meta.name = "demo";
        meta.totalTicks = 20;
        meta.chunks.put("c0.flashback", new ChunkMeta(20));

        byte[] chunk = ChunkWriter.write(new byte[]{9},
            List.of(new ReplayAction("flashback:action/next_tick", new byte[0])));

        try (var writer = FlashbackContainer.create(file)) {
            writer.writeMetadata(meta);
            writer.writeChunk("c0.flashback", chunk);
        }

        assertTrue(Files.exists(file));
        try (var reader = FlashbackContainer.open(file)) {
            assertEquals("demo", reader.readMetadata().name);
            assertArrayEquals(chunk, reader.readChunk("c0.flashback"));
            assertTrue(reader.entryNames().contains("metadata.json"));
            assertTrue(reader.entryNames().contains("c0.flashback"));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FlashbackContainerTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write `FlashbackContainer`**

```java
package dev.zeffut.flashbackserver.format;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class FlashbackContainer {

    public static Writer create(Path file) throws IOException {
        return new Writer(file);
    }

    public static Reader open(Path file) throws IOException {
        return new Reader(file);
    }

    public static final class Writer implements Closeable {
        private final ZipOutputStream zip;

        private Writer(Path file) throws IOException {
            this.zip = new ZipOutputStream(Files.newOutputStream(file));
        }

        public void writeMetadata(FlashbackMeta meta) throws IOException {
            putEntry("metadata.json", meta.toJson().getBytes(StandardCharsets.UTF_8));
        }

        public void writeChunk(String name, byte[] data) throws IOException {
            putEntry(name, data);
        }

        private void putEntry(String name, byte[] data) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(data);
            zip.closeEntry();
        }

        @Override public void close() throws IOException { zip.close(); }
    }

    public static final class Reader implements Closeable {
        private final ZipFile zip;

        private Reader(Path file) throws IOException {
            this.zip = new ZipFile(file.toFile());
        }

        public Set<String> entryNames() {
            Set<String> names = new LinkedHashSet<>();
            var e = zip.entries();
            while (e.hasMoreElements()) names.add(e.nextElement().getName());
            return names;
        }

        public FlashbackMeta readMetadata() throws IOException {
            return FlashbackMeta.fromJson(new String(read("metadata.json"), StandardCharsets.UTF_8));
        }

        public byte[] readChunk(String name) throws IOException { return read(name); }

        private byte[] read(String name) throws IOException {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) throw new IOException("Missing entry: " + name);
            try (InputStream in = zip.getInputStream(entry)) { return in.readAllBytes(); }
        }

        @Override public void close() throws IOException { zip.close(); }
    }

    private FlashbackContainer() {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*FlashbackContainerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/zeffut/flashbackserver/format/FlashbackContainer.java \
        src/test/java/dev/zeffut/flashbackserver/format/FlashbackContainerTest.java
git commit -m "feat: add Flashback ZIP container read/write"
```

---

### Task 7: `FlashbackValidator` — the autonomous test oracle

Given a `.flashback` file, assert structural validity. This is reused by every later
phase to verify produced recordings without a human.

**Files:**
- Create: `src/main/java/dev/zeffut/flashbackserver/format/FlashbackValidator.java`
- Test: `src/test/java/dev/zeffut/flashbackserver/format/FlashbackValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.zeffut.flashbackserver.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlashbackValidatorTest {
    private Path validReplay(Path dir) throws Exception {
        Path file = dir.resolve("valid.flashback");
        var meta = new FlashbackMeta();
        meta.name = "valid";
        meta.totalTicks = 2;
        meta.chunks.put("c0.flashback", new ChunkMeta(2));
        byte[] chunk = ChunkWriter.write(new byte[]{1}, List.of(
            new ReplayAction("flashback:action/next_tick", new byte[0]),
            new ReplayAction("flashback:action/next_tick", new byte[0])));
        try (var w = FlashbackContainer.create(file)) {
            w.writeMetadata(meta);
            w.writeChunk("c0.flashback", chunk);
        }
        return file;
    }

    @Test
    void acceptsValidReplay(@TempDir Path dir) throws Exception {
        FlashbackValidator.Report report = FlashbackValidator.validate(validReplay(dir));
        assertTrue(report.valid(), report.problems().toString());
        assertEquals(2, report.totalTicks());
        assertEquals(1, report.chunkCount());
    }

    @Test
    void rejectsMissingMetadataChunk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.flashback");
        var meta = new FlashbackMeta();
        meta.chunks.put("c0.flashback", new ChunkMeta(1)); // declared but not written
        try (var w = FlashbackContainer.create(file)) { w.writeMetadata(meta); }

        FlashbackValidator.Report report = FlashbackValidator.validate(file);
        assertFalse(report.valid());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("c0.flashback")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FlashbackValidatorTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write `FlashbackValidator`**

```java
package dev.zeffut.flashbackserver.format;

import java.nio.file.Path;
import java.util.*;

public final class FlashbackValidator {

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
                return new Report(false, problems, 0, 0);
            }

            FlashbackMeta meta = reader.readMetadata();
            totalTicks = meta.totalTicks;
            chunkCount = meta.chunks.size();

            int tickSum = 0;
            for (var entry : meta.chunks.entrySet()) {
                String name = entry.getKey();
                if (!entries.contains(name)) {
                    problems.add("declared chunk not present: " + name);
                    continue;
                }
                try {
                    ChunkReader.Result result = ChunkReader.read(reader.readChunk(name));
                    int ticks = (int) result.actions().stream()
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

        return new Report(problems.isEmpty(), problems, totalTicks, chunkCount);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*FlashbackValidatorTest'`
Expected: PASS.

- [ ] **Step 5: Run the full suite and build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/zeffut/flashbackserver/format/FlashbackValidator.java \
        src/test/java/dev/zeffut/flashbackserver/format/FlashbackValidatorTest.java
git commit -m "feat: add Flashback structural validator (test oracle)"
```

---

## Phase 0 exit criteria

- `./gradlew build` is green with all tests passing.
- `docs/format/flashback-format.md` documents the container format with the real MAGIC
  value and confirmed metadata keys.
- The `format` package can write a `.flashback` container and read it back losslessly
  (round-trip), with packet payloads treated as opaque bytes.
- `FlashbackValidator.validate(Path)` returns a structured report — the oracle that
  P1's integration harness and all later phases use to approve produced recordings.

## Known follow-ups (later phases, not P0)

- **Compression**: if Task 1 confirms chunk compression (zstd), add `zstd-jni` and wrap
  chunk read/write — the codec boundary is already isolated in `ChunkWriter/Reader`.
- **Reference-file test**: once a real `.flashback` (produced by the Flashback mod) is
  available, add a test that `FlashbackValidator.validate(...)` accepts it. This is the
  strongest interop check short of the P6 human spot-check.
- **Real packet bytes**: P2 fills `ReplayAction` payloads with actual serialized
  Minecraft play packets via Netty capture.
