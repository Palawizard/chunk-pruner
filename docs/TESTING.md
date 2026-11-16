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
| Committed | Superflat 1.20.1, 1.21.1 and 26.3, plus a small 1.21.1 world saved with `region-file-compression=lz4` | `core/src/test/fixtures/worlds/<name>/world`, with a `NOTE.md` per world | the core tests, on every pull request |
| Generated | One normal-terrain world per version, same script with `-LevelType normal` | `build/generated-worlds/`, made in the `Fixture worlds` CI job and never committed | the same S1, S2 and S6 tests, and the server check below |

A committed world is at most 2 MB as a zip archive. On disk they are about 15 MB each, because every chunk takes at least one 4 KiB sector and the start region of a vanilla server alone writes more than 2,000 chunks; git stores them compressed. The generated worlds have chunks over several sectors (up to 4 sectors in the local 1.21.1 run) and points of interest. The game writes a `.mcc` file only for a chunk of 1 MiB or more. None of the worlds made so far has one, and the CI job lists any it finds. A missing `.mcc` file is tested on a damaged copy (S6); reading a `.mcc` file that is there has no test on game-written data yet.

Each `NOTE.md` gives the server, the properties, the moves and their times, the RCON commands with the answers, and what the game wrote (files, chunks, statuses, `InhabitedTime` in three groups: 0, 1 to 199 ticks, 200 or more), measured with a script independent of the engine.

## Expected values

Expected counts and decisions never come from the engine. `core/src/test/java/.../fixture/ReferenceWorld` is a separate reader: it finds the dimension folders from what is on disk, decodes chunks with the JDK and lz4-java directly, and parses the whole NBT tree. `Expected` applies the rules of SPEC section 2 to what it reads. `ReferenceWorldTest` checks that reader against the counts in the notes and against how the worlds were made (the chunks with placed glass have a high `InhabitedTime`, the player data has three overworld points).

## Scenarios

The acceptance scenarios are listed in [SPEC.md](SPEC.md). Each one maps to at least one automated test. In M1:

| Scenario | Test |
|---|---|
| S1 | `DryRunFixtureTest`: every file of the world keeps its SHA-256 except the two new reports, and the report counts and sizes match `Expected`, with K8 at radius 0, 1 and 3 and with a K6 box. |
| S2 | `PruneFixtureTest`: prune on a copy by calling the engine (the command line refuses), with K1 alone, K1 and K8, and the defaults. The removed chunks are gone from `region`, `entities` and `poi`, every kept chunk has the same sector bytes, sizes match, other files are untouched. `scripts/check-pruned-world.ps1` then opens a pruned copy on the vanilla server of the same version, teleports the join client into a pruned area, checks the log for region errors and that the chunk was generated again. |
| S6 | `DamagedRegionFixtureTest`: on a copy of a real region file, one chunk gets an unknown compression id, one gets its data overwritten, one points to a missing `.mcc` file, one gets the custom id 127. They are kept (K7), warned about, and the rest is pruned. `Lz4FixtureTest` covers LZ4 with and without the library. |
| S7 | `SafetyFixtureTest`: a second Java process holds `session.lock` and the command line refuses with exit code 3 and writes nothing; a lock held in the same process is refused too. The Windows behaviour of the lock is described in ARCHITECTURE and has no test. |
| S11 | `SafetyFixtureTest`: free space is injected (`FreeSpace`); one byte under the size of the files to change plus 10 percent stops the prune before any change, a dry run warns, and the exact amount lets it run. |

The other scenarios belong to later milestones (PLAN). `SafetyFixtureTest` also checks that `java -jar` refuses to prune without `--dry-run`, and `check-release-artifacts.ps1` checks the same on each release JAR.

## CI time

The `Fixture worlds` job runs once per version, in parallel with the band jobs. Measured locally on 2026-11-16 (4 cores) for 1.21.1: making the normal-terrain world 1 min 51 s, the S1, S2 and S6 tests on both worlds about 1 min 40 s on top of the core tests, and each server check about 1 min. CI times are recorded here once measured on GitHub runners.
