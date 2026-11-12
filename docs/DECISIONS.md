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

Status: Accepted.

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
