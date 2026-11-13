# Functional specification

Status: draft v1.

## 1. Terms

- Chunk: one 16 by 16 column entry in a region file (`r.<rx>.<rz>.mca`, 32 by 32 chunks per file).
- Storages: each dimension folder has three Anvil storages with the same coordinates. `region/` holds terrain, block entities and structure data, `entities/` holds entities (1.17 and later), and `poi/` holds points of interest.
- Prune: remove a chunk's entry from all three storages, so the game generates it again on the next load.
- Run: one pass of the engine over a world, in `dry-run` or `prune` mode.

## 2. Keep rules

A chunk is kept if any rule matches. Rules are checked in order and the first match is recorded as the reason.

| Id | Rule | Default |
|---|---|---|
| K1 | `InhabitedTime` is above `inhabitedThresholdTicks` | threshold `0`, so only chunks with no simulation time at all can be pruned |
| K2 | The chunk is within `protectRadiusChunks` of a protected point: the world spawn, or a player's last position or respawn position (read from `playerdata/` and from the host data in `level.dat`) | radius `4` |
| K3 | The chunk is force-loaded, according to the dimension's forced-chunk or chunk-ticket storage (exact file per band, see ARCHITECTURE) | on |
| K4 | The chunk holds a structure start referenced by at least one kept chunk | on |
| K5 | Its `entities/` entry has an entity a player cares about: custom-named, tamed or owned, leashed, or marked persistent | on |
| K6 | It lies inside a protected box from the config (`protectedAreas`) | none |
| K7 | It cannot be read or understood: corrupt data, unknown compression, or a missing external `.mcc` file | always on |

Chunks whose generation status is not `full` (leftovers from generation) can be pruned under the same rules.

Only `minecraft:overworld`, `minecraft:the_nether` and `minecraft:the_end` are processed by default. Modded dimensions are added through `dimensions.include`, because some mods keep dimension data that does not regenerate.

## 3. Where a run happens

| Id | Context | Trigger | World state |
|---|---|---|---|
| E1 | Dedicated or integrated server startup | A pending schedule (from `/chunkpruner schedule`), or `autoPrune.enabled` with its interval elapsed | Before any level or chunk is loaded. The server's own session lock is held. |
| E2 | Singleplayer, Edit World screen | The "Prune unvisited chunks" button | The world is not open. The vanilla backup prompt comes first. |
| E3 | Command line: `java -jar chunk-pruner-<minecraft>-<loader>-<version>.jar --world <path> [--dry-run] [--config <file>]` | Manual | The world must not be in use. The tool refuses to run if another process holds `session.lock`. |

All three contexts call the same engine with the same config and write the same reports.

## 4. Run flow

1. Preflight.
   - Check that the world uses Anvil storage and, in E3, that it is not in use.
   - Skip, with a warning, any dimension that has region-like files but no `.mca` files (other storage formats).
   - Check free disk space: at least the size of the region files to modify, plus 10 percent. Otherwise stop.
2. Scan. Read the region headers and only the fields each chunk needs (§5). Build the protected points (K2), the forced set (K3) and the structure reference graph (K4).
3. Decide. Apply the keep rules and record a reason per chunk. A region is affected when at least one of its chunks will be pruned.
4. In dry-run mode, write the report and stop here.
5. Backup, unless `backup` is `false`. Copy each affected region file, with its `entities/` and `poi/` counterparts, to `chunkpruner/backups/<run-id>/<dimension>/<storage>/`. If a copy fails, stop.
6. Journal. Write `chunkpruner/journal.json` with the affected regions and their state (`pending` or `done`).
7. Rewrite each affected region in the three storages.
   - Write a compacted file with only the kept chunks. Their sectors are copied byte for byte, with no recompression, and the header is rebuilt.
   - Delete the file when no chunk is left in it.
   - Writes are atomic (`.tmp`, fsync, move). After each region, mark it `done` in the journal.
8. Finish. Write the report, delete the journal, apply backup retention and clear the schedule.

If a journal exists at startup (E1), or when E2 or E3 opens the world, the previous run was interrupted. The engine finishes the `pending` regions with the recorded decisions, then reports. A new run never starts while a journal exists.

## 5. Data read per chunk

From `region/`: `InhabitedTime`, `Status`, `xPos` and `zPos`, and the structure starts and references (exact keys per band, see ARCHITECTURE). From `entities/`, only when K5 is on: the entity list. Nothing else is decoded.

## 6. Reports

`chunkpruner/reports/<run-id>.json` and `.txt` contain:

- The run id, mode, context, duration, the Minecraft, loader and mod versions, and the config used.
- For each dimension: chunks scanned, chunks kept per rule, chunks pruned, region files changed and deleted, and the size of each storage before and after.
- Warnings: skipped dimensions, unreadable chunks, external `.mcc` files, low disk space.
- The `.txt` file starts with a short summary, for example: "Removed 48,211 unvisited chunks (6.3 GB) from 3 dimensions. Kept 12,904 chunks: 11,870 played in, 812 near players or spawn, 222 holding structures."

## 7. Commands (E1, permission level 4 by default)

| Command | Effect |
|---|---|
| `/chunkpruner schedule dry-run` | Run a dry run at the next startup. |
| `/chunkpruner schedule prune` | Prune at the next startup. Refused unless a dry-run report for this world is less than 7 days old. |
| `/chunkpruner cancel` | Remove the pending schedule. |
| `/chunkpruner status` | Show the pending schedule, the last run and the auto-prune state. |
| `/chunkpruner report [run-id]` | Print the summary of the latest report, or of a given one. |

A command never prunes a running world directly.

## 8. Singleplayer screen (E2, optional client part)

- A button on the vanilla Edit World screen opens the Chunk Pruner screen.
- The screen shows the config summary, then a Dry run button, then the report summary. The Prune button is enabled only after a dry run in the same session. Pruning starts with the vanilla backup prompt, then shows a progress bar and the result.
- All text uses translation keys. E1 and E3 work without the client part.

## 9. Configuration (`config/chunkpruner.json`)

| Key | Default | Meaning |
|---|---|---|
| `inhabitedThresholdTicks` | `0` | K1 threshold (20 ticks per second). |
| `protectRadiusChunks` | `4` | K2 radius. |
| `protectForcedChunks` | `true` | K3. |
| `protectStructures` | `true` | K4. |
| `protectPlayerEntities` | `true` | K5. |
| `protectedAreas` | `[]` | K6, as `{ dimension, minX, minZ, maxX, maxZ }` in block coordinates. |
| `dimensions.include` | the three vanilla dimensions | Dimensions to process. |
| `backup` | `true` | Back up affected files. |
| `backupRetention` | `3` | Number of run backups kept. |
| `autoPrune.enabled` | `false` | Periodic run at startup (E1). |
| `autoPrune.intervalDays` | `7` | Minimum days between automatic runs. |
| `autoPrune.mode` | `"dry-run"` | Must be set to `"prune"` explicitly to allow unattended pruning. |

## 10. Acceptance scenarios

| Id | Given | When | Then |
|---|---|---|---|
| S1 | A fixture world with explored areas and flown-over areas. | Dry run. | No file changes (checksums match). The report counts match the fixture's known values. |
| S2 | Same world. | Prune. | Only chunks eligible under K1 are removed from `region`, `entities` and `poi`. Every kept chunk is byte-identical. The world opens, and the removed areas generate again. |
| S3 | A tamed wolf sits in an otherwise unvisited chunk. | Prune. | The chunk is kept (K5). |
| S4 | A structure start chunk is unvisited, but one of its pieces lies in a visited chunk. | Prune. | The start chunk is kept (K4). |
| S5 | The process is killed during the rewrite. | Restart. | The journal resumes the run. Every region is either fully old or fully new, and the world opens. |
| S6 | A region has an unknown compression id or a corrupt chunk. | Prune. | That chunk is kept (K7). The run continues and the report warns. |
| S7 | The world is open in another process. | E3 runs. | It refuses with a clear message. |
| S8 | The modded dimension `examplemod:mining` is not in `dimensions.include`. | Prune. | That dimension is untouched. |
| S9 | Dedicated server, client without the mod. | Any. | The client joins normally. |
| S10 | A schedule exists, but the world has no recent dry run. | `schedule prune`. | Refused, with a message asking for a dry run first. |
| S11 | Not enough free space. | Prune. | Stops before any change. |
| S12 | LZ4-compressed regions (1.20.5 and later server setting). | Prune in game. | Works. In E3 without an LZ4 library, the affected chunks are kept (K7) and the report warns. |
