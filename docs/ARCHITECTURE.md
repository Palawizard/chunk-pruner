# Architecture

This document describes what the code does and where it hooks into the game. The folder layout follows ADR-001 in [DECISIONS.md](DECISIONS.md), which also records how the bands include the core.

## Overview

```text
core/        Pure Java, no Minecraft imports, JUnit tests:
               anvil/   region header, chunk reader, compression codecs
               nbt/     read-only streaming NBT reader that picks fields or whole subtrees
               world/   level.dat and player files, folder layout, session lock, forced chunks
               rules/   keep rules K1 to K8, structure graph, player entities
               engine/  preflight, scan, decide, backup, journal, rewrite, resume, retention
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
- The writer rebuilds a region by copying the raw sectors of kept chunks byte for byte, one chunk after the other in the order of their location entries, and only rebuilds the location table (timestamps are kept). It closes the old file before it moves the new one over it or deletes it. Kept chunks are never decompressed or recompressed. A region whose file, or whose `entities/` or `poi/` counterpart, has an entry outside the file or a truncated header is not rewritten: its chunks are kept under K7.
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

Chunk keys read by the engine, the same on the three versions (`ChunkSerializer` on 1.20.1 and 1.21.1, `SerializableChunkData` on 26.3): `xPos` and `zPos` (int, checked against the entry's position), `InhabitedTime` (long), `Status` (string such as `minecraft:full`), and the `structures` compound (K4, below). Section palettes are compounds with `Name` on 1.20.1 and 1.21.1 and plain strings on 26.3 (the engine does not read them; the test reader does).

An `entities/` entry (`EntityStorage`, same keys on the three versions) holds `Position` (int array of two, checked against the entry's position), `Entities` (a list of entity compounds, each with its `Passengers` inside it) and `DataVersion`.

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

## Keep rule data per version (K3, K4, K5)

Checked on 2026-11-16 in the decompiled sources of 1.20.1, 1.21.1 and 26.3 (Mojang names, Vineflower through Loom), in the Forge 47.4.28, NeoForge 21.1.266 and NeoForge 26.3.0.102 JARs (bytecode), and in the files the vanilla servers wrote for the keep fixture worlds (`/forceload`, entities summoned over RCON).

Forced chunks (K3), one file per dimension folder, gzip NBT with the saved data in `data` and a `DataVersion` next to it:

| | 1.20.1 and 1.21.1 | 26.3 |
|---|---|---|
| File | `data/chunks.dat` (`ForcedChunksSavedData`) | `data/minecraft/chunk_tickets.dat` (`TicketStorage`; `DimensionStorageFileFix` moves `chunks.dat` there) |
| Vanilla content | `Forced`: long array of packed chunk positions | `tickets`: list of `{chunk_pos: [x, z], type, level, ticks_left}`; only ticket types with the persist flag are saved (`minecraft:forced` from `/forceload`, `minecraft:portal`) |
| Loader content | Forge `ForgeForced` and Forge and NeoForge `ModForced`: lists of owners (`Mod` or `Controller`), each with a list of entries holding a `Chunk` long | NeoForge `neo_ticket_data` (`ForcedChunkManager.OwnedChunks`, written only when a mod forces chunks): a list of `{controller, block_chunks: [{position, tickets}], entity_chunks: [{uuid, tickets}]}`, where `tickets` is `{normal, natural_spawning}`, two long arrays of packed chunk positions |

Packed positions follow `ChunkPos.pack`: x in the low 32 bits, z in the high ones. The engine keeps every chunk of `Forced`, of a `Chunk` long under `ForgeForced` or `ModForced`, of every saved ticket, whatever its type, and of the `normal` and `natural_spawning` lists of `neo_ticket_data`. The NeoForge 26.3 layout comes from its sources (`neoforge-26.3.0.102-sources.jar`) and is tested on the real 26.3 file with such an entry added, not on a file NeoForge wrote. When the file cannot be read, holds any other key in `data`, or a `neo_ticket_data` of another shape, every chunk of the dimension counts as forced and the report warns (`forced-chunks-unknown`).

Structures (K4), in the chunk's `structures` compound, the same on the three versions:

- `starts`: one compound per structure id that starts in the chunk, with `id`, `ChunkX`, `ChunkZ`, `references` and `Children` (the pieces and their `BB`). The game writes only valid starts (`ChunkGenerator.tryGenerateStructure`); a start with `id` `INVALID` is a placeholder and does not count.
- `References`: one long array per structure id, the packed positions of the chunks that hold its start. The game drops a reference more than 8 chunks away when it loads the chunk; the engine keeps them all.

The structure graph stores, per dimension, the start ids of each chunk and its references as packed longs. Between the two passes it is resolved against the chunks the other rules keep: a start chunk referenced for its own structure by another kept chunk is kept (K4), and its own references count in turn. A start within 8 chunks of a chunk whose `region/` entry or file cannot be read is kept too, since that chunk's references are unknown.

Entities (K5):

| Key | 1.20.1 | 1.21.1 | 26.3 |
|---|---|---|---|
| Custom name | `CustomName`, a JSON string | `CustomName`, a JSON string | `CustomName`, a text component (a plain string for plain text) |
| Owner (`TamableAnimal`, `AbstractHorse`) | `Owner`, UUID as int array of four | same | same (`EntityReference`) |
| Leash | `Leash`: `{UUID}` or `{X, Y, Z}` (`Mob`) | `leash`: `{UUID}` or an int array of three (`Leashable`) | same as 1.21.1 |
| Persistence | `PersistenceRequired` (byte, always written by `Mob`) | same | same |
| Other marks | `Trusted` (fox), `Brain.memories."minecraft:liked_player"` (allay), `FromBucket` (fish, axolotl), `PlayerCreated` (iron golem) | same | same |
| Placed-by-generation markers | `ShowBottom` (end crystal), `LootTable` (chest minecart), `Item.id` (item frame) | same | same |
| Boat ids | `boat`, `chest_boat` | same | one per wood (`oak_boat`, `oak_chest_boat`, ...), `bamboo_raft`, `bamboo_chest_raft` |

`Owner` also exists on projectiles, items, area effect clouds and evoker fangs, where it is not a tamer; only an `Owner` next to `Health` (written by every `LivingEntity`) counts. Where world generation places entities, from the structure code (`SwampHutPiece`, `OceanMonumentPieces`, `OceanRuinPieces`, `WoodlandMansionPieces`, `EndCityPieces`, `MineshaftPieces`, `EndSpikeFeature`/`SpikeFeature`) and from the entities of the structure templates in the server JAR (`data/minecraft/structures/` on 1.20.1, `structure/` from 1.21): the same list on 1.20.1 and 1.21.1, plus cushions in abandoned camps and the zombie nautilus ridden by ocean ruin drowned on 26.3. The list and the rule are in SPEC section 2 and ADR-CP-007. The only leash vanilla adds without a player is the wandering trader's to its llamas (`WanderingTraderSpawner`).

## NBT reader

A streaming, read-only parser that extracts only the requested paths (`InhabitedTime`, `Status`, `xPos`, `zPos`, the spawn and player keys above, the `structures` compound, the `Entities` list, the saved data of the forced chunks) and skips every other payload without building a tree. A requested compound or list is kept whole, as maps, lists and arrays; any list or array in it longer than 65,536 elements makes the data unreadable, so a rule never works on part of it. It reads each chunk to its end, so a checksum or format error anywhere in the data makes the chunk unreadable. It refuses nesting deeper than 512, negative lengths, unknown tag types and data that ends early. A malformed chunk is kept under K7, and the parser never throws past the engine.

## Keep rules and run flow

A dimension is scanned in two passes. The first reads every chunk of `region/` (K1, K7 and the structure graph) and, when K5 is on, every entry of `entities/`, and reads the forced chunks (K3). Between the passes the structure graph is resolved (K4). The second pass decides each chunk (K8 needs the whole K1 set) and plans which entries leave `region/`, `entities/` and `poi/`. The plan, the free space check and the report are the same in a dry run and in a prune run.

A prune run then writes, in `<world>/chunkpruner/`:

1. `backups/<run-id>/<namespace>/<path>/<storage>/`: a copy of every file to change and of the `.mcc` files of the chunks that go, each forced to disk and its size compared with the original (unless `backup` is false).
2. `journal-plan.json`: for each affected region and storage file, the entries to remove (a 1024-bit set in hex), the size and modification time before, the size after, the external files to delete; and the report of the run. Written once.
3. `journal.json`: the state of each region, `pending` or `done`, written again after each region. The plan is kept apart so that this file stays small (about 80 bytes per region).
4. The rewrite of each region, its `region/` file first. Before each file, `Rewrite` tells apart a file as planned (same size and modification time: rewritten), as the plan leaves it (same size after and none of the removed entries: already done) and anything else (changed: the run stops, keeping its journal and backup).
5. The report, then the journal is deleted, then the backup retention.

When `journal.json` exists, `Engine.run` finishes that run instead of starting one, whatever the mode, and writes the recorded report with a `resumed` warning, unless the interrupted run had already written its report. The end of a run deletes the `.tmp` files a killed process may have left in `chunkpruner/` and `chunkpruner/reports/`. A backup that fails is deleted before the run stops. A complete backup ends with `restore.txt` (which folder of the backup goes back where, since the overworld is at the world root before data version 4772); a process killed while it copies leaves a folder without it, which the next retention deletes without counting it. The retention never deletes the backup of the run that just finished. The region writer is package-private in `engine/` (ADR-CP-008), so `Engine.run` is the only way to rewrite a region. A `RunListener` hears each step (backup written, journal written, temporary file written, file rewritten, region done); the S5 tests use it to stop a second process at those points, and the startup hook will use it for progress in M3.

## Hook points (to verify per band)

| Concern | Expected location | Notes |
|---|---|---|
| E1: run before levels load | The server lifecycle point after the world storage is opened and before levels are created and the spawn area is prepared. Candidates: a loader "server about to start" event, or a mixin at the start of the server's level loading method. | Must run before any chunk read. Check on all three loaders, on dedicated and integrated servers. |
| World and dimension paths | The level storage access for the world folder, and dimension path resolution for vanilla and modded dimensions | In E1 and E2, paths come from the game. The command line tool uses the documented folder layout. |
| Forced chunks (K3) | Read from the files in the core (Keep rule data per version) | In E1 the server holds them in memory too; the file on disk is what the last save wrote. |
| Player positions (K2) | The player files and, before 26.1, the host player in `level.dat` (see World data per version) | Read with the core NBT reader (gzip). |
| Edit World button (E2) | Each loader's screen init event or hook, on the Edit World screen | The client part is isolated so dedicated servers never load client classes. |

## Concurrency and safety

- The engine is single-threaded in v1. Reads may run in parallel later, but writes stay sequential.
- In E2 and E3 the engine takes the world's `session.lock` the way the game does (an exclusive `tryLock` on the file) and holds it for the whole run, so the game cannot open the world meanwhile. When another process holds it, the run is refused. Taking the lock does not change the file. A dry run does not create a missing `session.lock`; a run that rewrites files (a prune, or any run that finishes a journal) creates it as `DirectoryLock` does (the snowman character, then the lock), since the game would otherwise open the world and write region files the engine is replacing. On Linux and macOS this is an advisory `fcntl` lock, which the game and the tool both honour; this is what the S7 test checks with a second process. On Windows, Java locks with `LockFileEx`, which is mandatory: while the game holds the lock, the tool can open the file but its `tryLock` fails, and reading the locked bytes fails too. The engine never reads the file, so it refuses in the same way. That Windows behaviour is not covered by a test yet (no Windows runner runs the core tests).
- All writes are atomic: `.tmp`, `force`, move with `ATOMIC_MOVE`, then a best-effort force of the folder (Linux and macOS; Windows cannot open a folder this way). The backup is finished and checked (sizes compared) before any rewrite.
- The journal is written before the first rewrite and updated after each region. A process killed at any point leaves each file old or new; the S5 tests kill a second process with `destroyForcibly` at seven chosen steps and after four delays, on the three versions.
- In E1, server startup waits until the run is done, with progress logged every 5 percent or every 10 seconds.

## Side and metadata

The server part holds the startup hook and the commands, and the client part holds the optional E2 screen. Each loader's metadata declares the mod as not required on clients that join a server. The key names differ per loader and per band and are checked there.

## Performance and measurements

- The scan reads only headers and the needed fields, but decodes each payload to its end (a checksum or format error anywhere makes the chunk unreadable, K7). Memory stays bounded: one region at a time, a few decision bits per chunk, and the structure graph as packed longs.
- Scan speed, measured on 2026-11-16 with `fr.palawi.chunkpruner.core.fixture.ScanBenchmark` (test sources; `java -cp "$(cat core/build/fixture-classpath.txt)" fr.palawi.chunkpruner.core.fixture.ScanBenchmark 10 <world>...`): dry runs with the default config, after one warm-up run, so the files are in the page cache and the numbers measure the engine, not the disk. Linux container, Intel Xeon at 2.10 GHz, OpenJDK 21.0.12, one thread.

| World | Region files | Chunks | Mean dry run | Region files per second | Chunks per second |
|---|---|---|---|---|---|
| normal terrain 1.20.1 | 8 | 3,719 | 497 to 771 ms | 10 to 16 | 4,800 to 7,500 |
| normal terrain 1.21.1 | 8 | 3,230 | 371 to 378 ms | 21 | 8,500 |
| normal terrain 26.3 | 8 | 3,548 | 414 to 417 ms | 19 | 8,500 |
| superflat 1.20.1 (committed) | 8 | 3,912 | 157 ms | 51 | 25,000 |
| superflat 26.3 (committed) | 8 | 3,691 | 135 ms | 59 | 27,300 |

  The region files of these worlds are not full (400 to 500 chunks each). On normal terrain the time splits about evenly between zlib inflation and the NBT walk (about 200 ms each for the 57 MB the 1.21.1 world decompresses to); the structures and the entities add under 10 percent. A full region of normal terrain, 1,024 chunks, takes about 0.12 s, so about 8 full regions per second: the target of about 50 regions per second holds for superflat or sparse regions, not for full normal-terrain ones with every payload decoded. Reading regions in parallel is listed in the plan as later work.
- What K4 and K5 keep, measured by `KeepShareMeasurementTest` with the test reader on the normal-terrain worlds made in CI (the job prints the numbers): the share of the overworld chunks that K1 and K8 alone would prune that each rule keeps. Local runs of 2026-11-16, same script and seed as CI:

| World | Prunable by K1 and K8 | Kept by K4 | Kept by K5 | K5 without the world generation exception |
|---|---|---|---|---|
| normal terrain 1.20.1 | 3,090 | 2 (0.06 %) | 2 (0.06 %): 2 items on the ground | 8 (0.26 %): 6 mineshaft chest minecarts, 2 items |
| normal terrain 1.21.1 | 2,827 | 3 (0.11 %) | 0 | 7 (0.25 %): 7 mineshaft chest minecarts |
| normal terrain 26.3 | 2,739 | 4 (0.15 %) | 0 | 3 (0.11 %): 3 mineshaft chest minecarts |

  The test fails when K5 keeps more than 10 percent of those chunks on a world that was not made for K5.
