# Fixture world 1.21.1

Superflat 1.21.1 world with an explored strip, a flown-over area and a respawn point.

## How it was made

Made on 2026-11-16 by `scripts/make-fixture-world.ps1 -Minecraft 1.21.1` on the vanilla dedicated server 1.21.1 from Mojang (`server.jar`, SHA-1 `59353fb40c36d304f2035d51e7d6e6baa98dc05c`), with the join client from `testmod/join-bot/` as the only player (offline mode, name `Explorer`). Nothing in this folder was edited after the game wrote it. Tests copy it to a temporary folder before they run.

Server properties: online-mode=false, white-list=false, enable-rcon=true, level-seed=20261116, level-name=world, view-distance=5, simulation-distance=2, gamemode=creative, difficulty=peaceful, spawn-protection=0, level-type=minecraft\:flat (plus the RCON settings, with a random password).

Plan: strip, bot name Explorer, protocol 767, client view distance 5.

Timeline:

- server ready after 10 s
- joined at x=3 z=1 (the world spawn area)
- stayed 5 s at the join position
- explored strip: stop 1 at x=403 z=1, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 2 at x=419 z=1, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 3 at x=435 z=1, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 4 at x=451 z=1, 15 s, then a 5 by 5 glass fill under the player, 2 s
- flown-over area: stop 1 at x=-93 z=401, 1 s
- flown-over area: stop 2 at x=-61 z=401, 1 s
- flown-over area: stop 3 at x=-29 z=401, 1 s
- flown-over area: stop 4 at x=3 z=401, 1 s
- flown-over area: stop 5 at x=35 z=401, 1 s
- flown-over area: stop 6 at x=67 z=401, 1 s
- flown-over area: stop 7 at x=99 z=401, 1 s
- respawn point set at x=3 z=401
- back in the strip at x=451 z=1, 5 s
- kicked after 88 s online

RCON commands, in order (UTC time, command, answer):

```text
09:58:22  list  ->  There are 0 of a max of 20 players online:
09:58:32  tp Explorer 403 100 1  ->  Teleported Explorer to 403.500000, 100.000000, 1.500000
09:58:47  fill 401 99 -1 405 99 3 minecraft:glass  ->  Successfully filled 25 block(s)
09:58:49  tp Explorer 419 100 1  ->  Teleported Explorer to 419.500000, 100.000000, 1.500000
09:59:04  fill 417 99 -1 421 99 3 minecraft:glass  ->  Successfully filled 25 block(s)
09:59:06  tp Explorer 435 100 1  ->  Teleported Explorer to 435.500000, 100.000000, 1.500000
09:59:21  fill 433 99 -1 437 99 3 minecraft:glass  ->  Successfully filled 25 block(s)
09:59:23  tp Explorer 451 100 1  ->  Teleported Explorer to 451.500000, 100.000000, 1.500000
09:59:38  fill 449 99 -1 453 99 3 minecraft:glass  ->  Successfully filled 25 block(s)
09:59:40  tp Explorer -93 140 401  ->  Teleported Explorer to -92.500000, 140.000000, 401.500000
09:59:41  tp Explorer -61 140 401  ->  Teleported Explorer to -60.500000, 140.000000, 401.500000
09:59:42  tp Explorer -29 140 401  ->  Teleported Explorer to -28.500000, 140.000000, 401.500000
09:59:43  tp Explorer 3 140 401  ->  Teleported Explorer to 3.500000, 140.000000, 401.500000
09:59:44  tp Explorer 35 140 401  ->  Teleported Explorer to 35.500000, 140.000000, 401.500000
09:59:45  tp Explorer 67 140 401  ->  Teleported Explorer to 67.500000, 140.000000, 401.500000
09:59:46  tp Explorer 99 140 401  ->  Teleported Explorer to 99.500000, 140.000000, 401.500000
09:59:47  spawnpoint Explorer 3 100 401  ->  Set spawn point to 3, 100, 401 [0.0] in minecraft:overworld for Explorer
09:59:47  tp Explorer 451 100 1  ->  Teleported Explorer to 451.500000, 100.000000, 1.500000
09:59:52  kick Explorer Fixture done  ->  Kicked Explorer: Fixture done
09:59:52  save-all flush  ->  Saving the game (this may take a moment!)Saved the game
09:59:52  stop  ->  Stopping the server
```

## What the game wrote

Measured on the committed files with a Python script independent of the engine, kept outside the repository (it reads the region headers and the NBT). `ReferenceWorldTest` checks the chunk count and the `InhabitedTime` groups again with the Java test reader:

```text
. region 8 files, 0 empty, 3702 chunks, max sectors 2 compression {2: 3702} mcc 0
. entities 8 files, 5 empty, 12 chunks, max sectors 1 compression {2: 12} mcc 0
. poi 5 files, 5 empty, 0 chunks, max sectors 0 compression {} mcc 0
chunks 3702 compression {2: 3702}
status {'minecraft:structure_starts': 2064, 'minecraft:biomes': 270, 'minecraft:carvers': 246, 'minecraft:initialize_light': 222, 'minecraft:full': 900}
InhabitedTime zero 3343 low(1-199) 311 high(>=200) 48 max 1316
```

`InhabitedTime` groups: zero is 0, low is 1 to 199 ticks (under 10 seconds), high is 200 ticks or more.

Size: 15332168 bytes on disk, 1205725 bytes as a zip archive (`zip -9`). The limit for a committed fixture world is 2 MB zipped (docs/TESTING.md). On disk every chunk takes at least one 4 KiB sector, so the size on disk mostly counts chunks, and the start region of a vanilla server alone writes more than 2,000 of them.

## Use

This world is the committed layer of the fixtures (docs/TESTING.md): it is small, superflat, and checked on every pull request by the core tests. The expected values of those tests come from the separate test reader in `core/src/test/java/fr/palawi/chunkpruner/core/fixture/`, never from the engine. The normal-terrain worlds of the second layer are made in CI with the same script and are never committed.
