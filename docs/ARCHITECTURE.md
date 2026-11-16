# Architecture

This document describes what the code does and where it hooks into the game. The folder layout follows ADR-001 in [DECISIONS.md](DECISIONS.md), which also records how the bands include the core.

## Overview

```text
core/        Pure Java, no Minecraft imports, JUnit tests:
               anvil/   region header, chunk reader, compression codecs, region writer
               nbt/     read-only streaming NBT reader that picks fields
               world/   level.dat and player files, folder layout, session lock
               rules/   keep rules K1, K2, K6, K7 and K8 (K3 to K5 in M2)
               engine/  preflight, scan, decide, rewrite (backup and journal in M2)
               report/  JSON and text reports
               config/  JSON reader and writer, config file (no library, ADR-CP-005)
               cli/     command-line entrypoint (E3)
versions/    One standalone project per band and loader: startup hook (E1), commands,
             world and dimension paths, and the optional client screen (E2).
testmod/     Test tools that never ship: the headless join client (join-bot/).
scripts/     Build of every band, production-JAR smoke test, release artifact checks,
             fixture worlds on vanilla servers.
```

The engine is plain Java, so it behaves the same on every band, can be tested against fixture worlds, and runs from the command line. Only the startup hook, the commands and the singleplayer screen touch Minecraft or loader APIs.

## Anvil format (in the core)

Checked on 2026-11-16 in the decompiled sources of 1.20.1, 1.21.1 and 26.3 (`RegionFile`, `RegionFileVersion`), and on the files the vanilla servers of those versions wrote for the fixture worlds:

- A region file starts with 1024 location entries (a 3-byte sector offset and a 1-byte sector count) and 1024 timestamps, followed by 4 KiB sectors. The game writes empty (0-byte) files too, mostly in `entities/` and `poi/`; they hold no chunk. The game ignores an entry whose offset points into the header, whose count is 0, or whose first sector starts past the end of the file.
- A chunk payload is a 4-byte big-endian length (the data plus the type byte), a 1-byte compression type, then the data. The root of the data is a named compound (type 10, empty name), as written by `NbtIo.writeUnnamedTag`.
- Bit `0x80` in the type byte means the data is in `c.<chunkX>.<chunkZ>.mcc` next to the region file, with the compression id in the low 7 bits. The game does this when a chunk needs 256 sectors (1 MiB) or more; the entry then has one sector with a 5-byte stub (length 1, type id plus `0x80`). The `.mcc` file holds the compressed data without the 5-byte header. That file is pruned with the chunk. If it is missing, the chunk is kept under K7.
- The writer rebuilds a region by copying the raw sectors of kept chunks byte for byte, in their order, and only rebuilds the location table (timestamps are kept). Kept chunks are never decompressed or recompressed. A region whose file, or whose `entities/` or `poi/` counterpart, has an entry outside the file or a truncated header is not rewritten: its chunks are kept under K7.
- LZ4 uses `net.jpountz.lz4.LZ4BlockInputStream`, found by reflection: Minecraft bundles lz4-java from 1.20.5 (`org.lz4:lz4-java` 1.8.0 on 1.21.1, `at.yawk.lz4:lz4-java` 1.10.1 on 26.3, same package). The command line tool finds it only when it is on the class path. Without it, LZ4 chunks count as unreadable and are kept.

Compression ids, from `RegionFileVersion`:

| Id | Meaning | 1.20.1 | 1.21.1 | 26.3 |
|---|---|---|---|---|
| 1 | gzip | yes | yes | yes |
| 2 | zlib (default, `deflate`) | yes | yes | yes |
| 3 | none | yes | yes | yes |
| 4 | LZ4 (`region-file-compression=lz4`) | no | yes | yes |
| 127 | custom, followed by a namespaced id string; the game itself cannot read it | no | yes | yes |

Any other id is unknown to the game and to the engine (K7).

Chunk keys read by the engine, the same on the three versions (`ChunkSerializer` on 1.20.1 and 1.21.1, `SerializableChunkData` on 26.3): `xPos` and `zPos` (int, checked against the entry's position), `InhabitedTime` (long), `Status` (string such as `minecraft:full`). Structure data is in the `structures` compound with `starts` and `References`, read by K4 in M2. Section palettes are compounds with `Name` on 1.20.1 and 1.21.1 and plain strings on 26.3 (the engine does not read them; the test reader does).

## World data per version

Checked on 2026-11-16 in the decompiled sources (`DimensionType.getStorageFolder`, `LevelResource`, `PrimaryLevelData`, `ServerPlayer`, the 26.3 file fixes) and in the fixture worlds:

| | 1.20.1 and 1.21.1 | 26.3 |
|---|---|---|
| Dimension folders | overworld at the world root, `DIM-1`, `DIM1`, others in `dimensions/<namespace>/<path>/` | every dimension in `dimensions/<namespace>/<path>/` |
| Storages in a dimension folder | `region/`, `entities/`, `poi/` | same |
| Player files | `playerdata/<uuid>.dat` (gzip) | `players/data/<uuid>.dat` (gzip) |
| World spawn in `level.dat` | `Data.SpawnX`, `SpawnY`, `SpawnZ` (overworld) | `Data.spawn` with `dimension` and `pos` (int array of 3) |
| Host player | `Data.Player` in `level.dat` (singleplayer) | moved to `players/data/`, `level.dat` keeps `singleplayer_uuid` |
| Player last position | `Pos` (3 doubles; the vehicle's x and z when riding) and `Dimension` | same |
| Player respawn | `SpawnX`, `SpawnY`, `SpawnZ`, `SpawnDimension` (overworld when absent), `SpawnForced` | `respawn` with `dimension`, `pos` (int array), `yaw`, `pitch`, `forced` |
| Data version written | 3465 (1.20.1), 3955 (1.21.1) | 5023 |

The 26.3 layout comes with data version 4772 (a 26.1 snapshot): `DimensionStorageFileFix` and `PlayerStorageFileFix` move the folders before the game loads an older world, and `LevelDatToSavedDataFileFix` moves the host player out of `level.dat`. The engine picks the layout from `Data.DataVersion` in `level.dat`: 4772 or later uses the 26.3 column. It reads both kinds of spawn and respawn keys, whichever the file has.

`session.lock` is taken by `DirectoryLock`: the game writes a snowman character to the file and holds an exclusive `FileChannel.tryLock()` on it while the world is open, on the three versions.

## NBT reader

A streaming, read-only parser that extracts only the requested paths (`InhabitedTime`, `Status`, `xPos`, `zPos`, and the spawn and player keys above) and skips every other payload without building a tree. It reads each chunk to its end, so a checksum or format error anywhere in the data makes the chunk unreadable. It refuses nesting deeper than 512, negative lengths, unknown tag types and data that ends early. A malformed chunk is kept under K7, and the parser never throws past the engine.

## Keep rules and run flow in this version

K1, K2, K6, K7 and K8 are checked; K3, K4 and K5 come in M2 and every report says so. A dimension is scanned in two passes: the first reads every chunk of `region/` and records the chunks kept by K1, the second decides each chunk (K8 needs the whole K1 set) and plans which entries leave `region/`, `entities/` and `poi/`. The plan, the free space check and the report are the same in a dry run and in a prune run.

The rewrite has no backup and no journal yet (M2). Until then it is only reachable from the tests: the command line refuses to run without `--dry-run`, no mod entry point calls the engine, and the only other caller is the test-only `FixturePruneTool`, which `check-release-artifacts.ps1` refuses in a release JAR.

## Hook points (to verify per band)

| Concern | Expected location | Notes |
|---|---|---|
| E1: run before levels load | The server lifecycle point after the world storage is opened and before levels are created and the spawn area is prepared. Candidates: a loader "server about to start" event, or a mixin at the start of the server's level loading method. | Must run before any chunk read. Check on all three loaders, on dedicated and integrated servers. |
| World and dimension paths | The level storage access for the world folder, and dimension path resolution for vanilla and modded dimensions | In E1 and E2, paths come from the game. The command line tool uses the documented folder layout. |
| Forced chunks (K3) | The dimension's forced-chunk saved data. Older versions use a `chunks.dat`-style file under `data/`; 26.3 moves it to `data/minecraft/chunk_tickets.dat` in the dimension folder (`DimensionStorageFileFix`). | Record the format for each band here in M2. |
| Structure keys (K4) | The chunk's `structures` compound with `starts` and `References` | Same names on 1.20.1, 1.21.1 and 26.3 (checked on 2026-11-16); their content is read in M2. |
| Player positions (K2) | The player files and, before 26.1, the host player in `level.dat` (see World data per version) | Read with the core NBT reader (gzip). |
| Edit World button (E2) | Each loader's screen init event or hook, on the Edit World screen | The client part is isolated so dedicated servers never load client classes. |

## Concurrency and safety

- The engine is single-threaded in v1. Reads may run in parallel later, but writes stay sequential.
- In E2 and E3 the engine takes the world's `session.lock` the way the game does (an exclusive `tryLock` on the file) and holds it for the whole run, so the game cannot open the world meanwhile. When another process holds it, the run is refused. Taking the lock does not change the file, and a missing `session.lock` is not created. On Linux and macOS this is an advisory `fcntl` lock, which the game and the tool both honour; this is what the S7 test checks with a second process. On Windows, Java locks with `LockFileEx`, which is mandatory: while the game holds the lock, the tool can open the file but its `tryLock` fails, and reading the locked bytes fails too. The engine never reads the file, so it refuses in the same way. That Windows behaviour is not covered by a test yet (no Windows runner runs the core tests).
- All writes are atomic. The backup is finished and checked (sizes compared) before any rewrite.
- The journal is written before the first rewrite and updated after each region.
- In E1, server startup waits until the run is done, with progress logged every 5 percent or every 10 seconds.

## Side and metadata

The server part holds the startup hook and the commands, and the client part holds the optional E2 screen. Each loader's metadata declares the mod as not required on clients that join a server. The key names differ per loader and per band and are checked there.

## Performance

- The scan reads only headers and the needed fields. The target is about 50 regions per second on an SSD, as an order of magnitude to confirm with M2 benchmarks.
- Memory stays bounded: one region at a time, and only a few decision bits per chunk. The structure graph stores chunk positions as longs.
