# Architecture

This document describes what the code does and where it hooks into the game. The folder layout follows ADR-001 in [DECISIONS.md](DECISIONS.md), and the exact way the bands include the core is recorded there once settled.

## Overview

```text
core/        Pure Java, no Minecraft imports, JUnit tests:
               anvil/   region header and sector IO, compression codecs, writer
               nbt/     read-only streaming NBT reader that picks fields
               rules/   keep rules K1 to K7, protected points, structure graph
               engine/  preflight, scan, decide, backup, journal, rewrite
               report/  JSON and text reports
               config/  JSON config (Gson)
               cli/     command-line entrypoint (E3)
versions/    One standalone project per band and loader: startup hook (E1), commands,
             world and dimension paths, and the optional client screen (E2).
scripts/     Build of every band, production-JAR smoke test, release artifact checks.
```

The engine is plain Java, so it behaves the same on every band, can be tested against fixture worlds, and runs from the command line. Only the startup hook, the commands and the singleplayer screen touch Minecraft or loader APIs.

## Anvil format (in the core)

To check against the game and the community format documentation:

- A region file starts with 1024 location entries (a 3-byte sector offset and a 1-byte sector count) and 1024 timestamps, followed by 4 KiB sectors.
- A chunk payload is a 4-byte big-endian length, a 1-byte compression type, then the data. Types: `1` gzip, `2` zlib, `3` none, `4` LZ4 (1.20.5 and later), `127` custom codec on newer versions (treated as unknown, so the chunk is kept under K7).
- Bit `0x80` in the compression byte means the payload is stored in `c.<x>.<z>.mcc` next to the region file. That file is pruned with the chunk. If it is missing, the chunk is kept under K7.
- The writer rebuilds a region by copying the raw sectors of kept chunks byte for byte. Kept chunks are never decompressed or recompressed.
- LZ4 uses the library Minecraft bundles from 1.20.5 when running in game, and in the command line tool when it is on the classpath. Without it, LZ4 chunks count as unreadable and are kept.

## NBT reader

A streaming, read-only parser that extracts only the requested paths (`InhabitedTime`, `Status`, the structure keys) and skips every other payload without allocating. It enforces length and depth limits and bounds checks. A malformed chunk is kept under K7, and the parser never throws past the engine.

## Hook points (to verify per band)

| Concern | Expected location | Notes |
|---|---|---|
| E1: run before levels load | The server lifecycle point after the world storage is opened and before levels are created and the spawn area is prepared. Candidates: a loader "server about to start" event, or a mixin at the start of the server's level loading method. | Must run before any chunk read. Check on all three loaders, on dedicated and integrated servers. |
| World and dimension paths | The level storage access for the world folder, and dimension path resolution for vanilla and modded dimensions | In E1 and E2, paths come from the game. The command line tool uses the documented folder layout. |
| Forced chunks (K3) | The dimension's forced-chunk saved data. Older versions use a `chunks.dat`-style file under `data/`, newer versions may use a chunk-ticket storage. | Record the file name and format for each band here once verified. |
| Structure keys (K4) | The chunk's `structures` compound with its starts and references | Check key names per band. |
| Player positions (K2) | `playerdata/*.dat` and the host player in `level.dat` | Read with the core NBT reader (gzip). |
| Edit World button (E2) | Each loader's screen init event or hook, on the Edit World screen | The client part is isolated so dedicated servers never load client classes. |

## Concurrency and safety

- The engine is single-threaded in v1. Reads may run in parallel later, but writes stay sequential.
- All writes are atomic. The backup is finished and checked (sizes compared) before any rewrite.
- The journal is written before the first rewrite and updated after each region.
- In E1, server startup waits until the run is done, with progress logged every 5 percent or every 10 seconds.

## Side and metadata

The server part holds the startup hook and the commands, and the client part holds the optional E2 screen. Each loader's metadata declares the mod as not required on clients that join a server. The key names differ per loader and per band and are checked there.

## Performance

- The scan reads only headers and the needed fields. The target is about 50 regions per second on an SSD, as an order of magnitude to confirm with M2 benchmarks.
- Memory stays bounded: one region at a time, and only a few decision bits per chunk. The structure graph stores chunk positions as longs.
