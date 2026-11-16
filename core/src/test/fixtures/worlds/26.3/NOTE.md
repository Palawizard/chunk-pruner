# Fixture world 26.3

Superflat 26.3 world with an explored strip, a flown-over area and a respawn point. 26.3 keeps every dimension under `dimensions/<namespace>/<path>/` and player data under `players/data/`.

## How it was made

Made on 2026-11-16 by `scripts/make-fixture-world.ps1 -Minecraft 26.3` on the vanilla dedicated server 26.3 from Mojang (`server.jar`, SHA-1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`), with the join client from `testmod/join-bot/` as the only player (offline mode, name `Explorer`). Nothing in this folder was edited after the game wrote it. Tests copy it to a temporary folder before they run.

Server properties: online-mode=false, white-list=false, enable-rcon=true, level-seed=20261116, level-name=world, view-distance=5, simulation-distance=2, gamemode=creative, difficulty=peaceful, spawn-protection=0, level-type=minecraft\:flat (plus the RCON settings, with a random password).

Plan: strip, bot name Explorer, protocol 777, client view distance 5.

Timeline:

- server ready after 10 s
- joined at x=-3 z=9 (the world spawn area)
- stayed 5 s at the join position
- explored strip: stop 1 at x=397 z=9, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 2 at x=413 z=9, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 3 at x=429 z=9, 15 s, then a 5 by 5 glass fill under the player, 2 s
- explored strip: stop 4 at x=445 z=9, 15 s, then a 5 by 5 glass fill under the player, 2 s
- flown-over area: stop 1 at x=-99 z=409, 1 s
- flown-over area: stop 2 at x=-67 z=409, 1 s
- flown-over area: stop 3 at x=-35 z=409, 1 s
- flown-over area: stop 4 at x=-3 z=409, 1 s
- flown-over area: stop 5 at x=29 z=409, 1 s
- flown-over area: stop 6 at x=61 z=409, 1 s
- flown-over area: stop 7 at x=93 z=409, 1 s
- respawn point set at x=-3 z=409
- back in the strip at x=445 z=9, 5 s
- kicked after 88 s online

RCON commands, in order (UTC time, command, answer):

```text
10:00:06  list  ->  There are 0 of a max of 20 players online:
10:00:16  tp Explorer 397 100 9  ->  Teleported Explorer to 397.500000, 100.000000, 9.500000
10:00:31  fill 395 99 7 399 99 11 minecraft:glass  ->  Successfully filled 25 block(s)
10:00:33  tp Explorer 413 100 9  ->  Teleported Explorer to 413.500000, 100.000000, 9.500000
10:00:48  fill 411 99 7 415 99 11 minecraft:glass  ->  Successfully filled 25 block(s)
10:00:50  tp Explorer 429 100 9  ->  Teleported Explorer to 429.500000, 100.000000, 9.500000
10:01:05  fill 427 99 7 431 99 11 minecraft:glass  ->  Successfully filled 25 block(s)
10:01:07  tp Explorer 445 100 9  ->  Teleported Explorer to 445.500000, 100.000000, 9.500000
10:01:22  fill 443 99 7 447 99 11 minecraft:glass  ->  Successfully filled 25 block(s)
10:01:24  tp Explorer -99 140 409  ->  Teleported Explorer to -98.500000, 140.000000, 409.500000
10:01:25  tp Explorer -67 140 409  ->  Teleported Explorer to -66.500000, 140.000000, 409.500000
10:01:26  tp Explorer -35 140 409  ->  Teleported Explorer to -34.500000, 140.000000, 409.500000
10:01:27  tp Explorer -3 140 409  ->  Teleported Explorer to -2.500000, 140.000000, 409.500000
10:01:28  tp Explorer 29 140 409  ->  Teleported Explorer to 29.500000, 140.000000, 409.500000
10:01:29  tp Explorer 61 140 409  ->  Teleported Explorer to 61.500000, 140.000000, 409.500000
10:01:30  tp Explorer 93 140 409  ->  Teleported Explorer to 93.500000, 140.000000, 409.500000
10:01:31  spawnpoint Explorer -3 100 409  ->  Set spawn point to -3, 100, 409 [0.0, 0.0] in minecraft:overworld for Explorer
10:01:31  tp Explorer 445 100 9  ->  Teleported Explorer to 445.500000, 100.000000, 9.500000
10:01:36  kick Explorer Fixture done  ->  Kicked Explorer: Fixture done
10:01:37  save-all flush  ->  Saving the game (this may take a moment!)Saved the game
10:01:37  stop  ->  Stopping the server
```

## What the game wrote

Measured on the committed files with a Python script independent of the engine, kept outside the repository (it reads the region headers and the NBT). `ReferenceWorldTest` checks the chunk count and the `InhabitedTime` groups again with the Java test reader:

```text
dimensions/minecraft/overworld region 8 files, 0 empty, 3691 chunks, max sectors 2 compression {2: 3691} mcc 0
dimensions/minecraft/overworld entities 3 files, 0 empty, 8 chunks, max sectors 1 compression {2: 8} mcc 0
dimensions/minecraft/overworld poi no files
chunks 3691 compression {2: 3691}
status {'minecraft:structure_starts': 2064, 'minecraft:biomes': 270, 'minecraft:terrain': 246, 'minecraft:initialize_light': 222, 'minecraft:full': 889}
InhabitedTime zero 3113 low(1-199) 538 high(>=200) 40 max 1326
```

`InhabitedTime` groups: zero is 0, low is 1 to 199 ticks (under 10 seconds), high is 200 ticks or more.

Size: 15278992 bytes on disk, 1318196 bytes as a zip archive (`zip -9`). The limit for a committed fixture world is 2 MB zipped (docs/TESTING.md). On disk every chunk takes at least one 4 KiB sector, so the size on disk mostly counts chunks, and the start region of a vanilla server alone writes more than 2,000 of them.

## Use

This world is the committed layer of the fixtures (docs/TESTING.md): it is small, superflat, and checked on every pull request by the core tests. The expected values of those tests come from the separate test reader in `core/src/test/java/fr/palawi/chunkpruner/core/fixture/`, never from the engine. The normal-terrain worlds of the second layer are made in CI with the same script and are never committed.
