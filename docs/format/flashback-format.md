# Flashback file format (documented for interoperability)

> Learned by reading the public Flashback source (github.com/Moulberry/Flashback). No code was copied.
> Verified against Flashback master on 2026-06-03.

## Container

- **Extension:** `.flashback`
- **Packaging:** Standard ZIP archive (Java `ZipOutputStream`, compression level `Deflater.BEST_SPEED` / level 1)
- **Entries:**
  - `metadata.json` — UTF-8 JSON, see [metadata.json keys](#metadatajson-keys) below
  - `icon.png` — 64×64 PNG thumbnail (optional; our writer may omit it)
  - `c0.flashback`, `c1.flashback`, ... — binary chunk streams (see [Chunk binary layout](#chunk-binary-layout-cnflashback))
  - `level_chunk_caches/<index>` — raw binary chunk-packet cache files (optional; produced when chunk caching is active; our writer determines whether to include these)
  - `level_chunk_cache` — legacy single-file variant of the above (optional)

> **Note on temp folder vs. final archive:** During recording, chunk data is written into a temporary directory (`recordFolder`). `ReplayExporter` finalizes the recording by packing that directory into the ZIP and deleting the temp folder. The `.flashback` extension belongs to the final ZIP, not to an intermediate directory.

---

## Chunk binary layout (`cN.flashback`)

Each chunk entry inside the ZIP is a flat binary stream. Fields are big-endian unless noted.

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 | 4 bytes | `magic` | Fixed value `0xD780E884` (signed int32, see [Magic](#magic)) |
| 4 | varint | `action_count` | Number of entries in the action registry |
| varies | per entry | action registry | `action_count` entries, each an `Identifier` written via `writeIdentifier()` (namespace + `:` + path as a length-prefixed string in Minecraft's `FriendlyByteBuf` encoding); the entry's zero-based position in this list is its numeric ID for the rest of the file |
| varies | 4 bytes | `snapshot_size` | Size in bytes of the following snapshot block (int32, big-endian) |
| varies | `snapshot_size` bytes | snapshot data | Opaque sequence of action records (same format as the action stream below) representing the initial world state for this chunk |
| varies | until EOF | action stream | Repeating records; see [Action record format](#action-record-format) |

### Magic

```
MAGIC = 0xD780E884   (decimal: -679,478,140 as a signed int32)
```

Source: `com.moulberry.flashback.Flashback`, line 142.

### Identifier encoding (`writeIdentifier`)

Minecraft's `FriendlyByteBuf.writeIdentifier()` serialises a `ResourceLocation` / `Identifier` as a single UTF-8 string in the form `namespace:path`, preceded by a varint byte-length. The Flashback mod's own namespace is `flashback`.

### Action record format

```
[varint  action_id ]   — zero-based index into this chunk's action registry
[int32   payload_size] — number of bytes that follow (0 for zero-payload actions)
[bytes   payload     ] — action-specific data (absent when payload_size == 0)
```

### Tick-advancement action

The action with identifier `flashback:action/next_tick` signals the end of one game tick. It always has a zero-length payload and is written via `startAndFinishAction()` (varint ID + 4-byte zero size, nothing else). Each occurrence of this action advances playback by one tick (20 ticks = 1 second).

---

## Timing

- Tick-based; **20 ticks per second**. No wall-clock timestamps are stored in chunk data.
- A chunk spans at most **`CHUNK_LENGTH_SECONDS × 20` ticks** before a new chunk file is started. `CHUNK_LENGTH_SECONDS = 5 × 60 = 300 s`, so the default maximum chunk length is **6 000 ticks (300 seconds)**.
- A new chunk is also started when the player changes dimension or recording finishes.

---

## metadata.json keys

Top-level keys produced by `FlashbackMeta.toJson()`:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `uuid` | string (UUID) | yes | Unique identifier for this replay |
| `name` | string | yes | Human-readable replay name (default `"Unnamed"`) |
| `version_string` | string | no | Mod/game version string; omitted when `null` |
| `world_name` | string | no | Name of the world/server; omitted when `null` |
| `data_version` | int | conditional | Minecraft data version; present when `world_name` is set |
| `protocol_version` | int | no | Minecraft network protocol version; omitted when `0` |
| `bobby_world_name` | string | no | Bobby-mod world name; omitted when `null` |
| `total_ticks` | int | no | Total tick count across all chunks; omitted when `0` |
| `markers` | object | no | Map of tick-position (string integer key) → `ReplayMarker` object; omitted when empty |
| `distantHorizonPaths` | object | no | Map of string key → file path string for Distant Horizons integration; omitted when empty |
| `customNamespacesForRegistries` | object | no | Map of registry name → string array of namespaces; omitted when `null` |
| `chunks` | object | yes | Map of chunk filename (e.g. `"c0.flashback"`) → `FlashbackChunkMeta` object |

### `FlashbackChunkMeta` object

| Key | Type | Description |
|-----|------|-------------|
| `duration` | int | Duration of this chunk in ticks |
| `forcePlaySnapshot` | boolean | When `true`, the reader must replay the snapshot on every seek into this chunk |

---

## Compression

- **Container (ZIP):** Java `Deflate` at `Deflater.BEST_SPEED` (level 1) — lightweight compression applied to all ZIP entries by `ReplayExporter`.
- **Chunk binary stream:** No additional compression. The raw binary written into `cN.flashback` entries is **not** individually zstd- or gzip-compressed; it is compressed only by the outer ZIP.
- **Level-chunk-cache entries:** Also stored raw (no per-entry compression beyond the ZIP wrapper).

---

## Open questions / unconfirmed

- **`writeIdentifier` wire encoding:** The Minecraft `FriendlyByteBuf.writeIdentifier()` call is confirmed, but the exact byte-level wire format (e.g. whether it uses a two-part varint length scheme or a single string) depends on the Minecraft version's NIO implementation. Our writer must match whatever version the reader expects. Low risk — standard Minecraft codec.
- **`ReplayMarker` JSON shape:** The fields serialised inside each `markers` entry were not traced here; `FlashbackGson.COMPRESSED.toJsonTree(entry.getValue())` is used. This should be verified before implementing marker support.
- **`level_chunk_cache` (singular) vs. `level_chunk_caches/` (directory):** Both are handled by `ReplayExporter`. The singular file appears to be a legacy path; our writer should use the directory form. Not confirmed which readers still expect the legacy form.
- **Snapshot action encoding:** The snapshot block uses the same action-record format as the main stream. The exact set of actions written during snapshot vs. normal recording was not fully traced in this investigation.
