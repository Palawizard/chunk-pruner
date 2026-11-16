# Decisions

Architecture decision records for Chunk Pruner. Each record has a status: Proposed, Accepted or Superseded. New records are added at the end. A decision that changes gets a new record, and the old one is marked "Superseded by ADR-x".

## ADR-001: Build layout

Status: Accepted.

The project reuses the layout of Pala's Item Clear: one standalone Gradle project per band and loader under `versions/`, a `versions/version-matrix.json` file shared by the build, smoke and release tooling, and per-band Gradle wrappers. Unlike Pala's Item Clear, the 1.20.1 band is also a set of standalone projects instead of an Architectury Loom root.

The shared logic lives in a single pure-Java core that every band includes. Shared logic is never copied into a band.

How the bands include the core (settled in M0): `core/` is a standalone Gradle project with its own wrapper, where the JUnit tests and the formatting check run on Java 17. Each band adds `core/src/main/java` as a second source directory of its `main` source set, through `versions/shared/band.gradle`, and compiles it with the band's own Java release. The core classes end up in the band's JAR next to the loader code. `mod_version` and the other mod properties are read from the root `gradle.properties` by the core and by every band. The command-line entrypoint (E3) is in the core, and every band JAR names it as `Main-Class` (`mod_main_class` in the root `gradle.properties`), so `java -jar` works on any band's JAR with that band's Java version.

Why: the layout already ships 21 artifacts for Pala's Item Clear, with working smoke and release tooling, and avoiding Architectury keeps the runtime dependency list empty (ADR-003).

Why a source directory and not a subproject or a core JAR: the bands are separate Gradle builds on different Gradle versions (8.14 for ForgeGradle 6, 9.x for the others), so a shared subproject would need a composite build across those versions. A source directory couples nothing at build time. It also avoids jar-in-jar, shading and package relocation: each JAR holds one copy of the core, compiled for the band's Java version, and nothing else to load. The core's own build enforces this: it compiles with `--release 17` and has no Minecraft or loader class on its classpath, so a Minecraft import in the core fails there before any band builds.

## ADR-002: Pure core, thin loader code

Status: Accepted.

Logic that does not need Minecraft lives in the pure core and is unit tested. Loader code only adapts lifecycle events, commands, configuration paths and server access. Version differences stay inside the band that needs them.

## ADR-003: No runtime dependencies

Status: Accepted.

The mod ships without Architectury API, without configuration libraries and without Fabric API. It only uses the loader and the libraries bundled with Minecraft (Gson, SLF4J, and LZ4 on versions that bundle it). A library bundled inside the JAR (jar-in-jar) does not count as a runtime dependency, but each one needs its own record.

Why: a server owner or modpack maker can add the mod without pulling in anything else.

## ADR-004: Mojang names

Status: Accepted.

Every band uses Mojang official names, with Parchment allowed on obfuscated versions. Yarn is not used.

Why: from 26.1 Fabric only uses Mojang names, and a single naming scheme keeps the shared code identical across loaders.

## ADR-005: Configuration

Status: Accepted. Completed by ADR-CP-005, which replaces Gson with the core's own JSON code.

One file, `config/chunkpruner.json`, read and written with the Gson bundled with Minecraft. Unknown keys are kept, missing keys get defaults. No in-game configuration screen.

## ADR-006: License

Status: Accepted.

MIT, so any modpack can include and redistribute the mod.

## ADR-007: Releases

Status: Accepted.

The version lives in `gradle.properties`, together with `CHANGELOG.md` and `.github/release-notes/v<version>.md`. `dev` is fast-forwarded to `main`, and pushing a `v<version>` tag creates the GitHub release and publishes to Modrinth and CurseForge. Both steps only happen on the maintainer's request. Details are in [release-checklist.md](release-checklist.md).

## ADR-008: Publishing tool

Status: Accepted.

`Kir-Antipov/mc-publish@v3`, one file per band and loader, followed by a job that sets the Modrinth environment through the API, as on Pala's Item Clear.

## ADR-CP-001: Anvil and NBT handling in pure Java

Status: Accepted.

Region IO and NBT field reading live in the core and use no Minecraft classes.

Why: one implementation serves every band and the command line tool. Kept chunks can be copied without decoding, and the engine is tested against fixtures without starting the game.

## ADR-CP-002: Only prune worlds that are not loaded

Status: Accepted.

In game, a run only happens at the startup hook before levels load (E1) or from the world selection screen (E2).

Why: it rules out any race with chunk loading, saving, or other mods' caches.

## ADR-CP-003: Keep what cannot be read

Status: Accepted.

Anything the engine cannot read or understand is kept (K7): unknown compression, malformed NBT, missing external payloads.

Why: the worst case must always be a chunk kept that could have been removed, never the opposite.

## ADR-CP-004: Vanilla dimensions by default

Status: Accepted.

Only the overworld, the nether and the end are processed unless the user adds other dimensions.

Why: some mods keep dimension state outside chunk storage, and that state may not survive regeneration.

## ADR-009: Gradle JVM per project

Status: Accepted.

Each project in `versions/version-matrix.json` (every band and loader, and the core) has a `gradleJava` field: the Java version Gradle itself runs on. It is separate from `java`, the release the band compiles for. The build scripts and the CI set `JAVA_HOME` to that version before calling the project's wrapper, and the scripts refuse a Gradle 8.x project with a `gradleJava` above 24.

Current values: 21 for the ForgeGradle 6 bands (Forge 1.20.1 and 1.21.1, on Gradle 8.14), 21 for the core, and 25 for every other band (Gradle 9.x).

Why: running every project on one Java version does not work. Gradle 8.14 runs on Java 24 at most, and ForgeGradle 6 needs Gradle 8.x. Fabric Loom 1.18 and later declare that Gradle must run on Java 25, and from Minecraft 26.1 Fabric requires Java 25 for the Gradle JVM. A single Java 21 runtime would have kept the Fabric bands on Loom 1.17 and blocked any Minecraft version that needs a newer Loom.

## ADR-010: Declared Minecraft range

Status: Accepted.

Each band records two ranges in `versions/version-matrix.json`. `bandRange` is every Minecraft patch the band is meant to cover. `declaredRange` is what the band's metadata (`fabric.mod.json`, `mods.toml`, `neoforge.mods.toml`) actually accepts, through `minecraft_version_range` in the band's `gradle.properties`. `declaredRange` starts at the compile target and grows toward `bandRange` one patch at a time, as each patch passes the production-JAR smoke test on every loader of the band. `scripts/build-all-version-bands.ps1` fails when a band's `gradle.properties` and the matrix disagree.

Consequence: until the other patches are smoke tested, a release only installs on the compile target of each band (1.20.1, 1.21.1 and 26.3 for the Tier A bands).

Why: a loader refuses a JAR outside its declared range, which is a clear message for the player. Declaring an untested patch instead risks a crash or, worse for this mod, a silent failure on a version it was never run on.

## ADR-CP-005: JSON in the core

Status: Accepted. Completes ADR-005.

The config file and the JSON report are read and written by a small JSON reader and writer in the core (`core/config/Json`), with its own tests, instead of the Gson bundled with Minecraft. The mod and the command line tool use the same code and read the same file, `config/chunkpruner.json`. Unknown keys are kept, in their place, when the file is written back. Missing keys take their default. A known key with the wrong type or range is an error that names the key. A byte order mark at the start of the file is accepted.

Why: the command line tool (E3) runs with `java -jar` and only has the mod JAR on its class path, so Gson is not there. Bundling Gson in the JAR would need its own record (ADR-003) and would ship a second copy next to Minecraft's. The config is a dozen keys and the report a tree of numbers and strings, which a few hundred lines of tested code cover.

## ADR-CP-006: Keep the neighbours of played chunks (K8)

Status: Accepted.

K1 stays at threshold 0. A new rule, K8, keeps a chunk that K1 does not keep when it lies within `protectNeighborRadiusChunks` chunks (Chebyshev distance, default 1, 0 turns it off) of a chunk that K1 keeps. It has its own id and reason in the reports. The docs say that the value on disk is an underestimate and what a value of 0 does and does not prove (SPEC section 2).

Why: `InhabitedTime` on disk is lower than the time players spent near a chunk, and can be 0 next to them. In the decompiled sources of 1.20.1, 1.21.1 and 26.3, `ChunkAccess.incrementInhabitedTime` does not mark the chunk unsaved, and `ChunkMap.save` skips chunks that are not unsaved. A new chunk is saved a few seconds after it is generated (10 s cooldown), then only when its region data changes, so the value on disk is the value at that last save. This is MC-127407 ("InhabitedTime not always updating in some chunks", still open, https://bugs.mojang.com/browse/MC-127407). The Inhabitor mod fixes it in game so that pruning by inhabited time can be trusted (https://modrinth.com/mod/inhabitor), and ChunkCleaner documents the same problem for its own InhabitedTime threshold (https://github.com/zeroBzeroT/ChunkCleaner).

Measured on 2026-11-16 on the vanilla dedicated servers, with the join client standing about 40 s (800 ticks) at one place, `view-distance=5`, `simulation-distance=2`, and a glass `fill` over 4 chunks after 20 s. Of the 81 chunks within 4 chunks of the player:

| Version | At 0 | 1 to 99 | 100 or more | Highest |
|---|---|---|---|---|
| 1.20.1 | 9 | 6 | 66 | 732 |
| 1.21.1 | 0 | 31 | 50 | 718 |
| 26.3 | 0 | 34 | 47 | 764 |

The filled chunks have the highest values. `InhabitedTime` also grew 5 (1.20.1, 1.21.1) or 6 (26.3) chunks away from the player, beyond the simulation distance, and only the outer one or two rings of the loaded area stayed at 0.

A chunk at 0 was not saved again after a player came near, so no block of it was changed by a player, and the game generates its terrain again from the seed. Its entities are saved apart, in `entities/`, so they may have changed: extending K5 to the entities a player may have placed or moved is planned before pruning is offered. K8 keeps the gaps inside played areas, the 1.20.1 case above, at the cost of one ring of chunks around them.

Options considered: a higher K1 threshold by default (it does not keep the zero chunks inside a played area, and the right value depends on how long players stay); keeping every chunk that has an entity (it would keep most flown-over areas, where animals spawn at generation).

