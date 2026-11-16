# Fixture world 1.21.1-lz4

Superflat 1.21.1 world saved with `region-file-compression=lz4` (compression id 4): spawn area only.

## How it was made

Made on 2026-11-16 by `scripts/make-fixture-world.ps1 -Minecraft 1.21.1 -Name 1.21.1-lz4 -Plan spawn -Compression lz4` on the vanilla dedicated server 1.21.1 from Mojang (`server.jar`, SHA-1 `59353fb40c36d304f2035d51e7d6e6baa98dc05c`), with the join client from `testmod/join-bot/` as the only player (offline mode, name `Explorer`). Nothing in this folder was edited after the game wrote it. Tests copy it to a temporary folder before they run.

Server properties: online-mode=false, white-list=false, enable-rcon=true, level-seed=20261116, level-name=world, view-distance=5, simulation-distance=2, gamemode=creative, difficulty=peaceful, spawn-protection=0, region-file-compression=lz4, level-type=minecraft\:flat (plus the RCON settings, with a random password).

Plan: spawn, bot name Explorer, protocol 767, client view distance 5.

Timeline:

- server ready after 10 s
- joined at x=6 z=-6 (the world spawn area)
- stayed 10 s at the join position
- kicked after 13 s online

RCON commands, in order (UTC time, command, answer):

```text
09:49:40  list  ->  There are 0 of a max of 20 players online:
09:49:54  kick Explorer Fixture done  ->  Kicked Explorer: Fixture done
09:49:55  save-all flush  ->  Saving the game (this may take a moment!)Saved the game
09:49:55  stop  ->  Stopping the server
```

## What the game wrote

Measured on the committed files with a script independent of the engine (Python, reading the region headers and the NBT, with its own LZ4 block decoder for the lz4-java stream format):

```text
. region 4 files, 0 empty, 1369 chunks, max sectors 2 compression {4: 1369} mcc 0
. entities 4 files, 4 empty, 0 chunks, max sectors 0 compression {} mcc 0
. poi no files
chunks 1369 compression {4: 1369}
status {'minecraft:structure_starts': 928, 'minecraft:biomes': 80, 'minecraft:carvers': 72, 'minecraft:initialize_light': 64, 'minecraft:full': 225}
InhabitedTime zero 1297 low(1-199) 72 high(>=200) 0 max 6
```

`InhabitedTime` groups: zero is 0, low is 1 to 199 ticks (under 10 seconds), high is 200 ticks or more.

Size: 5649172 bytes on disk, 73194 bytes as a zip archive (`zip -9`). The limit for a committed fixture world is 2 MB zipped (docs/TESTING.md). On disk every chunk takes at least one 4 KiB sector, so the size on disk mostly counts chunks.

## Use

This world is the committed layer of the fixtures (docs/TESTING.md): it is small, superflat, and checked on every pull request by the core tests. The expected values of those tests come from the separate test reader in `core/src/test/java/fr/palawi/chunkpruner/core/fixture/`, never from the engine. The normal-terrain worlds of the second layer are made in CI with the same script and are never committed.
