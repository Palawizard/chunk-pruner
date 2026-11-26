# Testing

Chunk Pruner handles world region files. A regression can destroy someone's data, so tests are part of every change.

## Layers

| Layer | Covers | Runs |
|---|---|---|
| Unit tests (JUnit 6) | Pure logic in the shared core | Every pull request |
| Fixture tests | Behaviour against real files produced by the game | Every pull request |
| Production-JAR smoke test | The packaged JAR on a real dedicated server for each band and loader | Every band on every pull request and every push to `dev` or `main` |
| Dev-only test mod | Deliberately triggers the failures the mod handles, so the smoke test can reproduce them | Loaded only by the smoke harness, never published |

The smoke test installs the loader's real dedicated server, drops the packaged JAR (and the dev-only test mod when a scenario needs it), boots the server, runs the scenario and checks the result in the files and logs. Dev runs (`runServer`, `runGameTestServer`) are not used as a release gate: they gave non-reproducible results across loader toolchains on Pala's Item Clear.

## Rules

- Every bug fix comes with a test that fails without the fix.
- Fixtures are real files captured from the game, kept small, each with a short note saying how it was made (Minecraft version, loader, steps).
- Tests use temporary directories and never touch a real user directory.
- Destructive operations are tested in dry-run and in real mode, and the result is compared with the expected state.
- Smoke runs longer than five minutes run in the background.

## Fixture worlds

Fixture worlds are made by `scripts/make-fixture-world.ps1` on the vanilla dedicated server of each version, downloaded from Mojang and checked against the SHA-1 of the version manifest. The headless join client in `testmod/join-bot/` is the only player, and RCON moves it: a short stay at spawn, an explored strip (four stops of 15 s, each followed by a 5 by 5 glass `fill` under the player, as a player's block changes), a flown-over area (seven stops of 1 s), a respawn point set with `/spawnpoint`, and a last stop back in the strip. `view-distance` is 5 and `simulation-distance` 2, so the outer rings of the loaded area are generated but never simulated. Nothing is edited after the game writes it.

There are two layers:

| Layer | Worlds | Where | Checked by |
|---|---|---|---|
| Committed | Superflat 1.20.1, 1.21.1 and 26.3, a small 1.21.1 world saved with `region-file-compression=lz4`, and one keep world per version (`<version>-keep`, made with `-Plan keep`) | `core/src/test/fixtures/worlds/<name>/world`, with a `NOTE.md` per world | the core tests, on every pull request |
| Generated | One normal-terrain world per version, same script with `-LevelType normal` | `build/generated-worlds/`, made in the `Fixture worlds` CI job and never committed | the same S1, S2 and S6 tests, and the server check below |

A committed world is at most 2 MB as a zip archive. On disk they are about 15 MB each, because every chunk takes at least one 4 KiB sector and the start region of a vanilla server alone writes more than 2,000 chunks; git stores them compressed. The generated worlds have chunks over several sectors (up to 4 sectors in the local 1.21.1 run) and points of interest. The game writes a `.mcc` file only for a chunk of 1 MiB or more. None of the worlds made so far has one, and the CI job lists any it finds. A missing `.mcc` file is tested on a damaged copy (S6). `RegionEdgeFixtureTest` moves the data of real chunks to `.mcc` files the way the game stores a large chunk, and checks that they are read, pruned with their file or kept with it; it also covers a region whose entities file is cut and a region file cut inside its last chunk.

The keep worlds (`-Plan keep`) are set up over RCON: the player stands in chunk (8, 14) and places glass in chunk (5, 14), a street of the plains village that starts in chunk (0, 14), so that start stays unvisited (S4); chunk row z=-10 is force-loaded while the player is away, one setup is summoned per chunk (a tamed wolf for S3, a named pig, a leashed sheep, a persistent cow, an armor stand, an item frame, a minecart, a boat, two end crystals, an item, a chest minecart with a loot table, a plain cow), then the row is released except chunk (-9, -10), which stays force-loaded (K3); a datapack written before the first start adds the dimension `examplemod:mining`, which gets chunks from a short forceload (S8). A second row, z=-12, holds a fox that trusts the player, an allay that likes the player, an axolotl released from a bucket, an iron golem built by a player and one that is not, a horse ridden by a named pig and one ridden by a plain pig, a wandering trader with its llama leashed to it and a trader llama leashed to a fence. In the End, a forceload of the main island makes the game place the ten end spike crystals itself, and chunk row z=20 holds a crystal with its base shown, one without, and an item frame with an elytra. They are 1.60 to 1.70 MB zipped.

Each `NOTE.md` gives the server, the properties, the moves and their times, the RCON commands with the answers, and what the game wrote (files, chunks, statuses, `InhabitedTime` in three groups: 0, 1 to 199 ticks, 200 or more), measured with a script independent of the engine.

## Expected values

Expected counts and decisions never come from the engine. `core/src/test/java/.../fixture/ReferenceWorld` is a separate reader: it finds the dimension folders from what is on disk, decodes chunks with the JDK and lz4-java directly, and parses the whole NBT tree. `Expected` applies the rules of SPEC section 2 to what it reads. Reports are read back with Gson (a test dependency), not with the core's JSON code. `ReferenceWorldTest` checks that reader against the counts in the notes and against how the worlds were made (the chunks with placed glass have a high `InhabitedTime`, the player data has three overworld points).

## Scenarios

The acceptance scenarios are listed in [SPEC.md](SPEC.md). Each one maps to at least one automated test. In this version:

| Scenario | Test |
|---|---|
| S1 | `DryRunFixtureTest`: every file of the world keeps its SHA-256 except the two new reports, and the report counts and sizes match `Expected`, with K8 at radius 0, 1 and 3 and with a K6 box. |
| S2 | `PruneFixtureTest` (every committed and generated world, keep worlds included): prune on a copy by calling the engine, with the K2 radius at 0 and K8 off (K2 still keeps the chunk of each point), with K8 alone at radius 1, and with the defaults. The removed chunks are gone from `region`, `entities` and `poi`, every kept chunk has the same sector bytes, sizes match, other files are untouched. `scripts/check-pruned-world.ps1` then opens a pruned copy on the vanilla server of the same version, teleports the join client into a pruned area, checks that a `fill` there answers "Successfully filled" (the area is loaded) and that the client logs its kick with the reason, checks the log for region errors and that the chunk was generated again. |
| S3, S4 | `KeepRulesFixtureTest` on the keep worlds: the test reader sees each setup as it was made, the expected reasons are K5 for the tamed wolf (S3) and the other player entities and marks, K4 for the village start (S4), K3 for the forced chunk, and nothing for the plain cow, the empty chunk, the golem not built by a player, the plain rider and the trader's llama; a prune with the engine keeps exactly those chunks, and with `protectForcedChunks`, `protectStructures` and `protectPlayerEntities` off it removes them. In the End, every chunk with an end crystal (the end spike ones the game placed, and the two summoned) or the elytra frame is kept, and every other chunk goes. On the committed 1.20.1 world, the chunk with the village's persistent cows is kept (K5, ADR-CP-009). K4 and K5 also run through `Expected` on every world in S1 and S2, including the mineshaft minecarts of the generated worlds. `ReviewFixesFixtureTest` covers a start next to an unreadable chunk (K4), the NeoForge and unknown forced chunk data on real files changed in a known way, the session lock a prune creates and holds, and a retention that keeps the backup of the run that finished. |
| S5 | `InterruptedPruneFixtureTest` on the three superflat worlds: a second Java process (`PruneChild`) prunes a copy and is killed with `destroyForcibly` after the backup, after the journal, with a `.tmp` file written and not moved (first and third), after the first rewritten file, after the first and second regions, and after 0, 2, 10 and 40 ms past the journal. Each storage file is then byte for byte the old file or the one an uninterrupted prune makes, which the test first checks against the chunks `Expected` prunes; the next run (a dry run when a journal exists) finishes the run, and the world then matches the uninterrupted prune, with no journal and no `.tmp` file left. `scripts/check-pruned-world.ps1 -Interrupt` kills the prune after its first region, resumes it and opens the world on the vanilla server (CI, committed world of each version). `BackupJournalFixtureTest` checks the backup (every changed file, restoring it gives the world back), `backup` false, a backup that cannot be written (nothing changes), the order backup, journal, first rewrite, the retention, and a resume that stops on a file changed since the plan. |
| S6 | `DamagedRegionFixtureTest`: on a copy of a real region file, one chunk gets an unknown compression id, one gets its data overwritten, one points to a missing `.mcc` file, one gets the custom id 127. They are kept (K7), warned about, and the rest is pruned. `Lz4FixtureTest` covers LZ4 with and without the library. |
| S7 | `SafetyFixtureTest`: a second Java process holds `session.lock` and the command line refuses with exit code 3 and writes nothing; a lock held in the same process is refused too. `scripts/check-played-world.ps1` checks it with the world open in the real client (below). The Windows behaviour of the lock is described in ARCHITECTURE and has no test. |
| S8 | `KeepRulesFixtureTest`: with the default config every file of `examplemod:mining` keeps its SHA-256 after a prune; with the dimension in `dimensions.include` its files change. |
| S11 | `SafetyFixtureTest`: free space is injected (`FreeSpace`); one byte under the size of the files to change plus the largest of them, plus 10 percent, stops the prune before any change, a dry run warns, and the exact amount lets it run. |

S9, S10 and S12 in game depend on the in-game contexts (M3). `SafetyFixtureTest` also prunes through the command line, and `check-release-artifacts.ps1` runs a dry run and a prune with each release JAR on a copy of the committed world of its version. `NbtFieldReaderTest` covers the NBT nesting limit (512 levels read, 513 refused, through skipped and kept compounds and lists). `KeepShareMeasurementTest` writes what K4 and K5 keep to `core/build/measurements/` (ARCHITECTURE, Performance and measurements).

## Played world with the real client

`scripts/check-played-world.ps1 -Jar <1.21.1 band JAR>` checks the whole path on a world played with the real Minecraft client. It needs a desktop: it starts the real 1.21.1 client with the portablemc launcher 5.0.6 and an offline account (path in `-Launcher` or `CHUNKPRUNER_PORTABLEMC`, game files in `-ClientRoot` or `CHUNKPRUNER_CLIENT_ROOT`), so it never runs in CI. Its functions for the real client are in `scripts/real-client.ps1`.

1. Played world. On the vanilla 1.21.1 dedicated server (normal terrain, `view-distance` 6), the real client joins as `Player` and stands 40 s in a glass room that RCON builds on a stone floor, with a chest holding 7 diamonds and 2 golden apples, a cow named Daisy, a pig named Bacon, an armor stand with a diamond helmet and an item frame holding an emerald. The join client joins as `Builder`, stands 400 blocks east and RCON builds a cobblestone tower next to it. The real client then flies over a line of chunks 600 blocks south, one second per stop, and comes back to the room.
2. Dry run and prune of a copy with `java -jar <Jar>`: the dry run changes no region, entities or poi file; the prune removes chunks, keeps the room and the tower chunks, and removes chunks at the edges of the flown-over area.
3. The pruned copy opened on the server with the real client: RCON finds the stone floor, the glass, the chest items, Daisy, Bacon, the helmet, the item frame and the tower; the real client flies to a pruned chunk of the flown-over area, which the server generates again; the server log has no region error.
4. `restore.txt` followed on another pruned copy, line by line: every region, entities and poi file is byte for byte as before the prune.
5. Session lock on Linux: a copy of the played world is opened in singleplayer in the real client; the command line, with and without `--dry-run`, exits with code 3 and the message that the world is open, creates no `chunkpruner` folder and leaves every region, entities and poi file as it was.

Run on 2026-11-26 on Arch Linux with Hyprland: all five steps passed (3,674 chunks removed, 587 kept). Not covered yet: the session lock and an interrupted prune on Windows, and the scripts under Windows PowerShell 5.1 (CI only checks their syntax).

## CI time

The `Fixture worlds` job runs once per version, in parallel with the band jobs, on `ubuntu-latest`. Measured on 2026-11-16 (run 43852032461): 7 min 24 s for 1.20.1 (normal-terrain world 2 min 21 s, S1, S2 and S6 on both worlds 3 min 9 s, the two server checks 1 min 39 s), 6 min 44 s for 1.21.1 and 5 min 35 s for 26.3. The band jobs take 1 min 30 s to 4 min 20 s, so a pull request now waits about 7 min 45 s for its checks, about 3 min more than before the job existed.
