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
