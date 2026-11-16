# Fixture world 1.20.1

Superflat 1.20.1 world with an explored strip, a flown-over area and a respawn point.

## How it was made

Made on 2026-11-16 by `scripts/make-fixture-world.ps1 -Minecraft 1.20.1` on the vanilla dedicated server 1.20.1 from Mojang (`server.jar`, SHA-1 `84194a2f286ef7c14ed7ce0090dba59902951553`), with the join client from `testmod/join-bot/` as the only player (offline mode, name `Explorer`). Nothing in this folder was edited after the game wrote it. Tests copy it to a temporary folder before they run.

Server properties: online-mode=false, white-list=false, enable-rcon=true, level-seed=20261116, level-name=world, view-distance=5, simulation-distance=2, gamemode=creative, difficulty=peaceful, spawn-protection=0, level-type=minecraft\:flat (plus the RCON settings, with a random password).

Plan: strip, bot name Explorer, protocol 763, client view distance 5.

Timeline:

- server ready after 10 s
- joined at x=-7 z=-1 (the world spawn area)
- stayed 5 s at the join position
- explored strip: stop 1 at x=393 z=-1, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 2 at x=409 z=-1, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 3 at x=425 z=-1, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 4 at x=441 z=-1, 15 s, then a 5 by 5 glass fill under the player, 2 s
- flown-over area: stop 1 at x=-103 z=399, 1 s
- flown-over area: stop 2 at x=-71 z=399, 1 s
- flown-over area: stop 3 at x=-39 z=399, 1 s
- flown-over area: stop 4 at x=-7 z=399, 1 s
- flown-over area: stop 5 at x=25 z=399, 1 s
- flown-over area: stop 6 at x=57 z=399, 1 s
- flown-over area: stop 7 at x=89 z=399, 1 s
- respawn point set at x=-7 z=399
- back in the strip at x=441 z=-1, 5 s
- kicked after 88 s online

RCON commands, in order (UTC time, command, answer):

```text
09:56:38  list  ->  There are 0 of a max of 20 players online:
09:56:48  tp Explorer 393 100 -1  ->  Teleported Explorer to 393.500000, 100.000000, -0.500000
09:57:03  fill 391 99 -3 395 99 1 minecraft:glass  ->  Successfully filled 25 block(s)
09:57:05  tp Explorer 409 100 -1  ->  Teleported Explorer to 409.500000, 100.000000, -0.500000
09:57:20  fill 407 99 -3 411 99 1 minecraft:glass  ->  Successfully filled 25 block(s)
09:57:22  tp Explorer 425 100 -1  ->  Teleported Explorer to 425.500000, 100.000000, -0.500000
09:57:37  fill 423 99 -3 427 99 1 minecraft:glass  ->  Successfully filled 25 block(s)
09:57:39  tp Explorer 441 100 -1  ->  Teleported Explorer to 441.500000, 100.000000, -0.500000
09:57:54  fill 439 99 -3 443 99 1 minecraft:glass  ->  Successfully filled 25 block(s)
09:57:56  tp Explorer -103 140 399  ->  Teleported Explorer to -102.500000, 140.000000, 399.500000
09:57:57  tp Explorer -71 140 399  ->  Teleported Explorer to -70.500000, 140.000000, 399.500000
09:57:58  tp Explorer -39 140 399  ->  Teleported Explorer to -38.500000, 140.000000, 399.500000
09:57:59  tp Explorer -7 140 399  ->  Teleported Explorer to -6.500000, 140.000000, 399.500000
09:58:00  tp Explorer 25 140 399  ->  Teleported Explorer to 25.500000, 140.000000, 399.500000
09:58:01  tp Explorer 57 140 399  ->  Teleported Explorer to 57.500000, 140.000000, 399.500000
09:58:02  tp Explorer 89 140 399  ->  Teleported Explorer to 89.500000, 140.000000, 399.500000
09:58:03  spawnpoint Explorer -7 100 399  ->  Set spawn point to -7, 100, 399 [0.0] in minecraft:overworld for Explorer
09:58:03  tp Explorer 441 100 -1  ->  Teleported Explorer to 441.500000, 100.000000, -0.500000
09:58:08  kick Explorer Fixture done  ->  Kicked Explorer: Fixture done
09:58:08  save-all flush  ->  Saving the game (this may take a moment!)Saved the game
09:58:08  stop  ->  Stopping the server
```

## What the game wrote

Measured on the committed files with a Python script independent of the engine, kept outside the repository (it reads the region headers and the NBT). `ReferenceWorldTest` checks the chunk count and the `InhabitedTime` groups again with the Java test reader:

```text
. region 8 files, 0 empty, 3912 chunks, max sectors 2 compression {2: 3912} mcc 0
. entities 6 files, 3 empty, 10 chunks, max sectors 1 compression {2: 10} mcc 0
. poi 4 files, 4 empty, 0 chunks, max sectors 0 compression {} mcc 0
chunks 3912 compression {2: 3912}
status {'minecraft:structure_starts': 1924, 'minecraft:biomes': 252, 'minecraft:carvers': 279, 'minecraft:initialize_light': 256, 'minecraft:full': 1195, 'minecraft:spawn': 6}
InhabitedTime zero 3706 low(1-199) 158 high(>=200) 48 max 1349
```

`InhabitedTime` groups: zero is 0, low is 1 to 199 ticks (under 10 seconds), high is 200 ticks or more.

Size: 16183793 bytes on disk, 1227753 bytes as a zip archive (`zip -9`). The limit for a committed fixture world is 2 MB zipped (docs/TESTING.md). On disk every chunk takes at least one 4 KiB sector, so the size on disk mostly counts chunks, and the start region of a vanilla server alone writes more than 2,000 of them.

## Use

This world is the committed layer of the fixtures (docs/TESTING.md): it is small, superflat, and checked on every pull request by the core tests. The expected values of those tests come from the separate test reader in `core/src/test/java/fr/palawi/chunkpruner/core/fixture/`, never from the engine. The normal-terrain worlds of the second layer are made in CI with the same script and are never committed.
