# Fixture world 1.21.1-keep

Superflat 1.21.1 world for the keep rules K3, K4 and K5 and the scenarios S3, S4 and S8: a village street visited away from the village start, one setup per chunk along two rows of chunks, a force-loaded chunk, the End main island generated without a player, and a datapack dimension.

## How it was made

Made on 2026-11-16 by `scripts/make-fixture-world.ps1 -Minecraft 1.21.1 -Plan keep -Name 1.21.1-keep` on the vanilla dedicated server 1.21.1 from Mojang (`server.jar`, SHA-1 `59353fb40c36d304f2035d51e7d6e6baa98dc05c`), with the join client from `testmod/join-bot/` as the only player (offline mode, name `Explorer`). Every setup below was made over RCON while the server ran; nothing in this folder was edited after the game wrote it. Tests copy it to a temporary folder before they run.

Before the first start, the script writes the datapack `world/datapacks/examplemod` (`pack.mcmeta` and `data/examplemod/dimension/mining.json`: the overworld dimension type with a flat generator of bedrock and stone). The server enables it when it creates the world and adds the dimension `examplemod:mining`.

Server properties: online-mode=false, white-list=false, level-seed=20261116, level-name=world, view-distance=5, simulation-distance=2, gamemode=creative, difficulty=peaceful, spawn-protection=0, level-type=minecraft\:flat (plus the RCON settings, with a random password).

Plan: keep, bot name Explorer, protocol 767, client view distance 5.

Timeline:

- server ready after 9 s
- joined at x=9 z=5 (the world spawn area)
- stayed 5 s at the join position
- village: stood in chunk (8, 14) at x=136 z=232 for 15 s, then a 5 by 5 glass fill at y=-55 in chunk (5, 14), a street of the village that starts in chunk (0, 14)
- chunk (-7, -10): summon wolf -104 -60 -152 {NoAI:1b,Sitting:1b,Owner:[I;648404627,-1965476445,-1249237152,-678858269]}
- chunk (-6, -10): summon pig -88 -60 -152 {NoAI:1b,CustomName:'"Bacon"'}
- chunk (-5, -10): summon sheep -70 -60 -152 {NoAI:1b,leash:[I;-72,-60,-152]}
- chunk (-4, -10): summon cow -56 -60 -152 {NoAI:1b,PersistenceRequired:1b}
- chunk (-3, -10): summon armor_stand -40 -60 -152
- chunk (-2, -10): summon item_frame -24 -60 -152 {Facing:1b,Item:{id:"minecraft:diamond",count:1}}
- chunk (-1, -10): summon minecart -8 -60 -152
- chunk (0, -10): summon boat 8 -60 -152
- chunk (1, -10): summon end_crystal 24 -60 -152 {ShowBottom:0b}
- chunk (2, -10): summon item 40 -60 -152 {Age:-32768s,Item:{id:"minecraft:diamond",count:1}}
- chunk (3, -10): summon chest_minecart 56 -60 -152 {LootTable:"minecraft:chests/abandoned_mineshaft"}
- chunk (4, -10): summon end_crystal 72 -60 -152 {ShowBottom:1b}
- chunk (5, -10): summon cow 88 -60 -152 {NoAI:1b}
- chunk (-7, -12): summon fox -104 -60 -184 {NoAI:1b,Trusted:[[I;648404627,-1965476445,-1249237152,-678858269]]}
- chunk (-6, -12): summon allay -88 -60 -184 {NoAI:1b,Brain:{memories:{"minecraft:liked_player":{value:[I;648404627,-1965476445,-1249237152,-678858269]}}}}
- chunk (-5, -12): summon axolotl -72 -60 -184 {NoAI:1b,FromBucket:1b}
- chunk (-4, -12): summon iron_golem -56 -60 -184 {NoAI:1b,PlayerCreated:1b}
- chunk (-3, -12): summon iron_golem -40 -60 -184 {NoAI:1b}
- chunk (-2, -12): summon horse -24 -60 -184 {NoAI:1b,Passengers:[{id:"minecraft:pig",NoAI:1b,CustomName:'"Bacon"'}]}
- chunk (-1, -12): summon horse -8 -60 -184 {NoAI:1b,Passengers:[{id:"minecraft:pig",NoAI:1b}]}
- chunk (0, -12): summon wandering_trader 8 -60 -184 {NoAI:1b,DespawnDelay:48000,UUID:[I;1,2,3,4]}
- chunk (0, -12): summon trader_llama 10 -60 -184 {NoAI:1b,leash:{UUID:[I;1,2,3,4]}}
- chunk (1, -12): summon trader_llama 26 -60 -184 {NoAI:1b,leash:[I;24,-60,-184]}
- removed the forceload of chunks x=-7 to 1, z=-12
- the End, chunk (0, 20): obsidian at y=64, then summon end_crystal 8 65 328 {ShowBottom:1b}
- the End, chunk (1, 20): obsidian at y=64, then summon end_crystal 24 65 328 {ShowBottom:0b}
- the End, chunk (2, 20): obsidian at y=64, then summon item_frame 40 65 328 {Facing:1b,Item:{id:"minecraft:elytra",count:1}}
- the End: chunks -3 to 2 (main island) force-loaded for 18 s and chunks x=0 to 3, z=20 for 8 s, then released
- removed the forceload of chunks x=-7 to 6, z=-10; forceload kept on chunk (-9, -10) (K3)
- examplemod:mining: chunk (0, 0) force-loaded for 5 s, then released
- kicked after 67 s online

Why the setups are where they are:

- The plains village found by `locate` starts in chunk (0, 14); with this seed its layout is the same on 1.20.1, 1.21.1 and 26.3, and chunk (5, 14) holds one of its streets. The player stood in chunk (8, 14), so the start chunk stayed beyond the view distance (5) and was only generated to an early status, never simulated. The glass fill made the game save chunk (5, 14) with the time the player spent near it (MC-127407).
- Chunk rows z=-10 and z=-12 were force-loaded while the player stood 24 chunks away, so their chunks were loaded without a player near enough to raise `InhabitedTime`. NoAI keeps each mob in its chunk. After the summons the rows were released, and only chunk (-9, -10) stayed force-loaded.
- The wandering trader has the `DespawnDelay` of 48000 ticks that `WanderingTraderSpawner` gives a real one: a trader llama leashed to a trader takes the trader's delay, and a delay of 0 removes it at once.
- In the End, the forceload of chunks -3 to 2 made the game generate the main island with its end spikes, whose crystals the game placed itself. The obsidian under the setups of chunk row z=20 holds the item frame up.
- The datapack dimension got chunks from a forceload of 5 seconds.

RCON commands, in order (UTC time, command, answer):

```text
14:39:42  list  ->  There are 0 of a max of 20 players online:
14:39:52  locate structure minecraft:village_plains  ->  The nearest minecraft:village_plains is at [0, ~, 224] (224 blocks away)
14:39:52  tp Explorer 136 -50 232  ->  Teleported Explorer to 136.500000, -50.000000, 232.500000
14:40:07  fill 86 -55 230 90 -55 234 minecraft:glass  ->  Successfully filled 25 block(s)
14:40:09  forceload add -112 -160 111 -145  ->  Marked 14 chunks in minecraft:overworld from [-7, -10] to [6, -10] to be force loaded
14:40:12  summon wolf -104 -60 -152 {NoAI:1b,Sitting:1b,Owner:[I;648404627,-1965476445,-1249237152,-678858269]}  ->  Summoned new Wolf
14:40:12  summon pig -88 -60 -152 {NoAI:1b,CustomName:'"Bacon"'}  ->  Summoned new Bacon
14:40:12  setblock -72 -60 -152 minecraft:oak_fence  ->  Changed the block at -72, -60, -152
14:40:12  summon sheep -70 -60 -152 {NoAI:1b,leash:[I;-72,-60,-152]}  ->  Summoned new Sheep
14:40:12  summon cow -56 -60 -152 {NoAI:1b,PersistenceRequired:1b}  ->  Summoned new Cow
14:40:12  summon armor_stand -40 -60 -152  ->  Summoned new Armor Stand
14:40:12  summon item_frame -24 -60 -152 {Facing:1b,Item:{id:"minecraft:diamond",count:1}}  ->  Summoned new Item Frame
14:40:12  summon minecart -8 -60 -152  ->  Summoned new Minecart
14:40:12  summon boat 8 -60 -152  ->  Summoned new Oak Boat
14:40:12  summon end_crystal 24 -60 -152 {ShowBottom:0b}  ->  Summoned new End Crystal
14:40:12  summon item 40 -60 -152 {Age:-32768s,Item:{id:"minecraft:diamond",count:1}}  ->  Summoned new Diamond
14:40:12  summon chest_minecart 56 -60 -152 {LootTable:"minecraft:chests/abandoned_mineshaft"}  ->  Summoned new Minecart with Chest
14:40:12  summon end_crystal 72 -60 -152 {ShowBottom:1b}  ->  Summoned new End Crystal
14:40:12  summon cow 88 -60 -152 {NoAI:1b}  ->  Summoned new Cow
14:40:12  forceload add -112 -192 31 -177  ->  Marked 9 chunks in minecraft:overworld from [-7, -12] to [1, -12] to be force loaded
14:40:15  summon fox -104 -60 -184 {NoAI:1b,Trusted:[[I;648404627,-1965476445,-1249237152,-678858269]]}  ->  Summoned new Fox
14:40:15  summon allay -88 -60 -184 {NoAI:1b,Brain:{memories:{"minecraft:liked_player":{value:[I;648404627,-1965476445,-1249237152,-678858269]}}}}  ->  Summoned new Allay
14:40:15  summon axolotl -72 -60 -184 {NoAI:1b,FromBucket:1b}  ->  Summoned new Axolotl
14:40:15  summon iron_golem -56 -60 -184 {NoAI:1b,PlayerCreated:1b}  ->  Summoned new Iron Golem
14:40:15  summon iron_golem -40 -60 -184 {NoAI:1b}  ->  Summoned new Iron Golem
14:40:15  summon horse -24 -60 -184 {NoAI:1b,Passengers:[{id:"minecraft:pig",NoAI:1b,CustomName:'"Bacon"'}]}  ->  Summoned new Horse
14:40:15  summon horse -8 -60 -184 {NoAI:1b,Passengers:[{id:"minecraft:pig",NoAI:1b}]}  ->  Summoned new Horse
14:40:15  summon wandering_trader 8 -60 -184 {NoAI:1b,DespawnDelay:48000,UUID:[I;1,2,3,4]}  ->  Summoned new Wandering Trader
14:40:15  summon trader_llama 10 -60 -184 {NoAI:1b,leash:{UUID:[I;1,2,3,4]}}  ->  Summoned new Trader Llama
14:40:15  setblock 24 -60 -184 minecraft:oak_fence  ->  Changed the block at 24, -60, -184
14:40:15  summon trader_llama 26 -60 -184 {NoAI:1b,leash:[I;24,-60,-184]}  ->  Summoned new Trader Llama
14:40:20  forceload remove -112 -192 31 -177  ->  Unmarked 9 chunks in minecraft:overworld from [-7, -12] to [1, -12] for force loading
14:40:21  execute in minecraft:the_end run forceload add -48 -48 47 47  ->  Marked 36 chunks in minecraft:the_end from [-3, -3] to [2, 2] to be force loaded
14:40:32  execute in minecraft:the_end run forceload add 0 320 63 335  ->  Marked 4 chunks in minecraft:the_end from [0, 20] to [3, 20] to be force loaded
14:40:35  execute in minecraft:the_end run setblock 8 64 328 minecraft:obsidian  ->  Changed the block at 8, 64, 328
14:40:35  execute in minecraft:the_end run summon end_crystal 8 65 328 {ShowBottom:1b}  ->  Summoned new End Crystal
14:40:35  execute in minecraft:the_end run setblock 24 64 328 minecraft:obsidian  ->  Changed the block at 24, 64, 328
14:40:35  execute in minecraft:the_end run summon end_crystal 24 65 328 {ShowBottom:0b}  ->  Summoned new End Crystal
14:40:35  execute in minecraft:the_end run setblock 40 64 328 minecraft:obsidian  ->  Changed the block at 40, 64, 328
14:40:35  execute in minecraft:the_end run summon item_frame 40 65 328 {Facing:1b,Item:{id:"minecraft:elytra",count:1}}  ->  Summoned new Item Frame
14:40:40  execute in minecraft:the_end run forceload remove all  ->  Unmarked all force loaded chunks in minecraft:the_end
14:40:40  forceload remove -112 -160 111 -145  ->  Unmarked 14 chunks in minecraft:overworld from [-7, -10] to [6, -10] for force loading
14:40:40  forceload add -136 -152  ->  Marked chunk [-9, -10] in minecraft:overworld to be force loaded
14:40:43  execute in examplemod:mining run forceload add 8 8  ->  Marked chunk [0, 0] in examplemod:mining to be force loaded
14:40:48  execute in examplemod:mining run forceload remove 8 8  ->  Unmarked chunk [0, 0] in examplemod:mining for force loading
14:40:51  kick Explorer Fixture done  ->  Kicked Explorer: Fixture done
14:40:52  save-all flush  ->  Saving the game (this may take a moment!)Saved the game
14:40:52  stop  ->  Stopping the server
```

## What the game wrote

Measured on the committed files with a Python script independent of the engine, kept outside the repository (it reads the region headers and the NBT):

```text
. region 6 files, 0 empty, 2453 chunks, max sectors 2 compression {2: 2453} mcc 0
. entities 4 files, 1 empty, 27 chunks, max sectors 1 compression {2: 27} mcc 0
. poi 4 files, 3 empty, 3 chunks, max sectors 1 compression {2: 3} mcc 0
. status {'minecraft:structure_starts': 1464, 'minecraft:biomes': 148, 'minecraft:carvers': 140, 'minecraft:initialize_light': 132, 'minecraft:full': 569}
. InhabitedTime zero 2260 low(1-199) 192 high(>=200) 1 max 289
DIM1 region 6 files, 0 empty, 1573 chunks, max sectors 1 compression {2: 1573} mcc 0
DIM1 entities 4 files, 0 empty, 13 chunks, max sectors 1 compression {2: 13} mcc 0
DIM1 status {'minecraft:structure_starts': 1163, 'minecraft:biomes': 106, 'minecraft:carvers': 90, 'minecraft:initialize_light': 74, 'minecraft:full': 140}
DIM1 InhabitedTime zero 1573 low(1-199) 0 high(>=200) 0 max 0
dimensions/examplemod/mining region 4 files, 0 empty, 729 chunks, max sectors 3 compression {2: 729} mcc 0
dimensions/examplemod/mining entities 4 files, 4 empty, 0 chunks, max sectors 0 compression {} mcc 0
dimensions/examplemod/mining status {'minecraft:structure_starts': 608, 'minecraft:biomes': 40, 'minecraft:carvers': 32, 'minecraft:initialize_light': 24, 'minecraft:full': 25}
dimensions/examplemod/mining InhabitedTime zero 729 low(1-199) 0 high(>=200) 0 max 0
```

`InhabitedTime` groups: zero is 0, low is 1 to 199 ticks (under 10 seconds), high is 200 ticks or more.

Checked with the same script, and again by `KeepRulesFixtureTest` with the Java test reader:

- Chunk (0, 14) holds the start `minecraft:village_plains` with `InhabitedTime` 0; chunk (5, 14) has `InhabitedTime` above 0 and references that start.
- Every chunk of rows z=-10 (x=-7 to 6) and z=-12 (x=-7 to 1) has `InhabitedTime` 0, and the entities of each are in that chunk's `entities/` entry: `CustomName` `"Bacon"` (a JSON string), the sheep `leash` as an int array [x, y, z], the boat id `minecraft:boat`, item counts in `count`. The wolf has `Owner` as an int array of four (the player's UUID), the persistent cow `PersistenceRequired` 1, the crystals `ShowBottom` 0 and 1, the chest minecart its `LootTable`, the fox `Trusted` with the player's UUID, the allay the `minecraft:liked_player` memory, the axolotl `FromBucket` 1, the golems `PlayerCreated` 1 and 0, the pigs ride the horses as `Passengers`, and both trader llamas are there, one leashed to the trader, one to the fence.
- In the End, every chunk has `InhabitedTime` 0; the ten end spike crystals have `ShowBottom` 1; chunk row z=20 holds the two summoned crystals and the item frame with an elytra.
- `data/chunks.dat` holds `data.Forced` = [-38654705673], chunk (-9, -10) packed as in `ChunkPos.pack` (x in the low 32 bits); the End and the datapack dimension have no forced chunk left.

Size: 19875239 bytes on disk, 1599488 bytes as a zip archive (`zip -9`). The limit for a committed fixture world is 2 MB zipped (docs/TESTING.md).

## Use

`KeepRulesFixtureTest` checks the decisions on this world against how it was made, and the S1, S2 and S6 tests run on it like on the other committed worlds. The expected values come from the separate test reader in `core/src/test/java/fr/palawi/chunkpruner/core/fixture/`, never from the engine.
