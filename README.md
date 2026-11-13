# Chunk Pruner

### Shrinks Minecraft worlds by removing chunks that nobody ever played in

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20%20to%2026.3-62B47A?logo=minecraft&logoColor=white)](docs/SUPPORT.md)
[![Loaders](https://img.shields.io/badge/Loaders-Forge%20%7C%20Fabric%20%7C%20NeoForge-2d6a4f)](docs/SUPPORT.md)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

In development. There is no release yet.

---

## What it does

Every chunk records how long players have spent near it (its inhabited time). Chunks that were only seen from a distance, while flying or during pregeneration, have an inhabited time of zero and usually make up most of a world's size. Chunk Pruner removes them while the world is not loaded. The game generates them again, from the same seed, when someone goes there.

Chunks near players, spawn, force-loaded areas and structures in use are always kept, and so is anything the mod cannot read. Every run can be done as a dry run first, and every modified file is backed up.

Areas that are pruned after new worldgen mods are added will generate with those mods next time.

- Runs at server startup, on a schedule, from the singleplayer world menu, or as a command-line tool.
- Required on the server only. On a client it only adds the singleplayer screen.
- Adds no blocks, items or registry entries. No dependencies beyond the loader.

---

## Installation

1. Download the JAR that matches your Minecraft version band and loader from the releases page (see [docs/SUPPORT.md](docs/SUPPORT.md)).
2. Put it in the server's `mods` folder.
3. Start the server once to create `config/chunkpruner.json`.

---

## Usage

On a server, schedule a run and restart:

| Command | Purpose |
| --- | --- |
| `/chunkpruner schedule dry-run` | Report what would be removed at the next startup |
| `/chunkpruner schedule prune` | Prune at the next startup (needs a recent dry run) |
| `/chunkpruner cancel` | Cancel the pending run |
| `/chunkpruner status` | Show the pending run and the last result |
| `/chunkpruner report [run-id]` | Show a report summary |

In singleplayer, use the "Prune unvisited chunks" button on the Edit World screen.

From a terminal, with the world closed:

```sh
java -jar chunk-pruner-<minecraft>-<loader>-<version>.jar --world <path-to-world> --dry-run
```

Commands require permission level 4 by default. The exact rules are in [docs/SPEC.md](docs/SPEC.md).

---

## Documentation

- [docs/SPEC.md](docs/SPEC.md): keep rules, run flow and acceptance scenarios
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): code layout, region format and hook points
- [docs/SUPPORT.md](docs/SUPPORT.md): supported versions and loaders
- [docs/TESTING.md](docs/TESTING.md): test strategy
- [docs/DECISIONS.md](docs/DECISIONS.md): decision records
- [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/release-checklist.md](docs/release-checklist.md)

---

## License

Distributed under the [MIT License](LICENSE).
