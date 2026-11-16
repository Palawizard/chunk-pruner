# Fixture world 26.3-keep

Superflat 26.3 world for the keep rules K3, K4 and K5 and the scenarios S3, S4 and S8: a village street visited away from the village start, one setup per chunk along a row of chunks, a force-loaded chunk and a datapack dimension.

## How it was made

Made on 2026-11-16 by `scripts/make-fixture-world.ps1 -Minecraft 26.3 -Plan keep -Name 26.3-keep` on the vanilla dedicated server 26.3 from Mojang (`server.jar`, SHA-1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`), with the join client from `testmod/join-bot/` as the only player (offline mode, name `Explorer`). Every setup below was made over RCON while the server ran; nothing in this folder was edited after the game wrote it. Tests copy it to a temporary folder before they run.

Before the first start, the script writes the datapack `world/datapacks/examplemod` (`pack.mcmeta` and `data/examplemod/dimension/mining.json`: the overworld dimension type with a flat generator of bedrock and stone). The server enables it when it creates the world and adds the dimension `examplemod:mining`.

Server properties: online-mode=false, white-list=false, level-seed=20261116, level-name=world, view-distance=5, simulation-distance=2, gamemode=creative, difficulty=peaceful, spawn-protection=0, level-type=minecraft\:flat (plus the RCON settings, with a random password).

Plan: keep, bot name Explorer, protocol 777, client view distance 5.

Timeline:

- server ready after 9 s
- joined at x=9 z=6 (the world spawn area)
- stayed 5 s at the join position
- village: stood in chunk (8, 14) at x=136 z=232 for 15 s, then a 5 by 5 glass fill at y=-55 in chunk (5, 14), a street of the village that starts in chunk (0, 14)
- chunk (-7, -10): summon wolf -104 -60 -152 {NoAI:1b,Sitting:1b,Owner:[I;648404627,-1965476445,-1249237152,-678858269]}
- chunk (-6, -10): summon pig -88 -60 -152 {NoAI:1b,CustomName:"Bacon"}
- chunk (-5, -10): summon sheep -70 -60 -152 {NoAI:1b,leash:[I;-72,-60,-152]}
- chunk (-4, -10): summon cow -56 -60 -152 {NoAI:1b,PersistenceRequired:1b}
- chunk (-3, -10): summon armor_stand -40 -60 -152
- chunk (-2, -10): summon item_frame -24 -60 -152 {Facing:1b,Item:{id:"minecraft:diamond",count:1}}
- chunk (-1, -10): summon minecart -8 -60 -152
- chunk (0, -10): summon oak_boat 8 -60 -152
- chunk (1, -10): summon end_crystal 24 -60 -152 {ShowBottom:0b}
- chunk (2, -10): summon item 40 -60 -152 {Age:-32768s,Item:{id:"minecraft:diamond",count:1}}
- chunk (3, -10): summon chest_minecart 56 -60 -152 {LootTable:"minecraft:chests/abandoned_mineshaft"}
- chunk (4, -10): summon end_crystal 72 -60 -152 {ShowBottom:1b}
- chunk (5, -10): summon cow 88 -60 -152 {NoAI:1b}
- removed the forceload of chunks x=-7 to 6, z=-10; forceload kept on chunk (-9, -10) (K3)
- examplemod:mining: chunk (0, 0) force-loaded for 5 s, then released
- kicked after 44 s online

Why the setups are where they are:

- The plains village found by `locate` starts in chunk (0, 14); with this seed its layout is the same on 1.20.1, 1.21.1 and 26.3, and chunk (5, 14) holds one of its streets. The player stood in chunk (8, 14), so the start chunk stayed beyond the view distance (5) and was only generated to an early status, never simulated. The glass fill made the game save chunk (5, 14) with the time the player spent near it (MC-127407).
- Chunk row z=-10 was force-loaded while the player stood 24 chunks away, so its chunks were loaded without a player near enough to raise `InhabitedTime`. NoAI keeps each mob in its chunk. After the summons the row was released, and only chunk (-9, -10) stayed force-loaded.
- The datapack dimension got chunks from a forceload of 5 seconds.

RCON commands, in order (UTC time, command, answer):

```text
13:19:38  list  ->  There are 0 of a max of 20 players online:
13:19:48  locate structure minecraft:village_plains  ->  The nearest minecraft:village_plains is at [0, ~, 224] (224 blocks away)
13:19:48  tp Explorer 136 -50 232  ->  Teleported Explorer to 136.500000, -50.000000, 232.500000
13:20:03  fill 86 -55 230 90 -55 234 minecraft:glass  ->  Successfully filled 25 block(s)
13:20:05  forceload add -112 -160 111 -145  ->  Marked 14 chunks in minecraft:overworld from [-7, -10] to [6, -10] to be force loaded
13:20:09  summon wolf -104 -60 -152 {NoAI:1b,Sitting:1b,Owner:[I;648404627,-1965476445,-1249237152,-678858269]}  ->  Summoned new Wolf
13:20:09  summon pig -88 -60 -152 {NoAI:1b,CustomName:"Bacon"}  ->  Summoned new Bacon
13:20:09  setblock -72 -60 -152 minecraft:oak_fence  ->  Changed the block at -72, -60, -152
13:20:09  summon sheep -70 -60 -152 {NoAI:1b,leash:[I;-72,-60,-152]}  ->  Summoned new Sheep
13:20:09  summon cow -56 -60 -152 {NoAI:1b,PersistenceRequired:1b}  ->  Summoned new Cow
13:20:09  summon armor_stand -40 -60 -152  ->  Summoned new Armor Stand
13:20:09  summon item_frame -24 -60 -152 {Facing:1b,Item:{id:"minecraft:diamond",count:1}}  ->  Summoned new Item Frame
13:20:09  summon minecart -8 -60 -152  ->  Summoned new Minecart
13:20:09  summon oak_boat 8 -60 -152  ->  Summoned new Oak Boat
13:20:09  summon end_crystal 24 -60 -152 {ShowBottom:0b}  ->  Summoned new End Crystal
13:20:09  summon item 40 -60 -152 {Age:-32768s,Item:{id:"minecraft:diamond",count:1}}  ->  Summoned new Diamond
13:20:09  summon chest_minecart 56 -60 -152 {LootTable:"minecraft:chests/abandoned_mineshaft"}  ->  Summoned new Minecart with Chest
13:20:09  summon end_crystal 72 -60 -152 {ShowBottom:1b}  ->  Summoned new End Crystal
13:20:09  summon cow 88 -60 -152 {NoAI:1b}  ->  Summoned new Cow
13:20:14  forceload remove -112 -160 111 -145  ->  Unmarked 14 chunks in minecraft:overworld from [-7, -10] to [6, -10] for force loading
13:20:14  forceload add -136 -152  ->  Marked chunk [-9, -10] in minecraft:overworld to be force loaded
13:20:17  execute in examplemod:mining run forceload add 8 8  ->  Marked chunk [0, 0] in examplemod:mining to be force loaded
13:20:22  execute in examplemod:mining run forceload remove 8 8  ->  Unmarked chunk [0, 0] in examplemod:mining for force loading
13:20:25  kick Explorer Fixture done  ->  Kicked Explorer: Fixture done
13:20:26  save-all flush  ->  Saving the game (this may take a moment!)Saved the game
13:20:26  stop  ->  Stopping the server
```

## What the game wrote

Measured on the committed files with a Python script independent of the engine, kept outside the repository (it reads the region headers and the NBT):

```text
dimensions/examplemod/mining region 4 files, 0 empty, 729 chunks, max sectors 3 compression {2: 729} mcc 0
dimensions/examplemod/mining status {'minecraft:structure_starts': 608, 'minecraft:biomes': 40, 'minecraft:terrain': 32, 'minecraft:initialize_light': 24, 'minecraft:full': 25}
dimensions/examplemod/mining InhabitedTime zero 729 low(1-199) 0 high(>=200) 0 max 0
dimensions/minecraft/overworld region 6 files, 0 empty, 2383 chunks, max sectors 2 compression {2: 2383} mcc 0
dimensions/minecraft/overworld entities 3 files, 0 empty, 17 chunks, max sectors 1 compression {2: 17} mcc 0
dimensions/minecraft/overworld poi 1 files, 0 empty, 3 chunks, max sectors 1 compression {2: 3} mcc 0
dimensions/minecraft/overworld status {'minecraft:structure_starts': 1432, 'minecraft:biomes': 144, 'minecraft:terrain': 136, 'minecraft:initialize_light': 128, 'minecraft:full': 543}
dimensions/minecraft/overworld InhabitedTime zero 2074 low(1-199) 308 high(>=200) 1 max 293
```

`InhabitedTime` groups: zero is 0, low is 1 to 199 ticks (under 10 seconds), high is 200 ticks or more.

Checked with the same script, and again by `KeepRulesFixtureTest` with the Java test reader:

- Chunk (0, 14) holds the start `minecraft:village_plains` with `InhabitedTime` 0; chunk (5, 14) has `InhabitedTime` above 0 and references that start.
- Every chunk of row z=-10, x=-7 to 6, has `InhabitedTime` 0, and the entities of each are in that chunk's `entities/` entry: `CustomName` `Bacon` (a plain string), the sheep `leash` as an int array [x, y, z], the boat id `minecraft:oak_boat`, item counts in `count`. The wolf has `Owner` as an int array of four (the player's UUID), the persistent cow `PersistenceRequired` 1, the crystals `ShowBottom` 0 and 1, the chest minecart its `LootTable`.
- `dimensions/minecraft/overworld/data/minecraft/chunk_tickets.dat` holds one ticket: `chunk_pos` [-9, -10], `type` `minecraft:forced`, `level` 31.

Size: 12973527 bytes on disk, 1107358 bytes as a zip archive (`zip -9`). The limit for a committed fixture world is 2 MB zipped (docs/TESTING.md).

## Use

`KeepRulesFixtureTest` checks the decisions on this world against how it was made, and the S1, S2 and S6 tests run on it like on the other committed worlds. The expected values come from the separate test reader in `core/src/test/java/fr/palawi/chunkpruner/core/fixture/`, never from the engine.
