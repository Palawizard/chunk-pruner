[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('1.20.1', '1.21.1', '26.3')]
    [string]$Minecraft,
    # Folder name under core/src/test/fixtures/worlds. Defaults to the Minecraft version.
    [string]$Name,
    # 'strip': an explored strip and a flown-over area. 'spawn': a short stay at spawn only.
    # 'keep': a village piece visited away from its start, entities, a forced chunk and the
    # datapack dimension examplemod:mining, all set up over RCON (K3, K4, K5, S3, S4, S8).
    [ValidateSet('strip', 'spawn', 'keep')]
    [string]$Plan = 'strip',
    # Value of region-file-compression (1.20.5 and later). Empty keeps the server default.
    [ValidateSet('', 'deflate', 'lz4', 'none')]
    [string]$Compression = '',
    # 'flat' for the committed fixtures, 'normal' for the worlds generated in CI.
    [ValidateSet('flat', 'normal')]
    [string]$LevelType = 'flat',
    # Where <Name>/world is written. Defaults to core/src/test/fixtures/worlds.
    [string]$OutputRoot,
    [long]$Seed = 20261116,
    [int]$ViewDistance = 5,
    [int]$SimulationDistance = 2,
    [int]$ServerPort = 25600,
    [int]$RconPort = 25610,
    [switch]$Force
)

# Makes a fixture world with the real vanilla dedicated server: the headless join client
# (testmod/join-bot) joins as one player, RCON moves it, then the server saves and stops.
# The world is copied to <OutputRoot>/<Name>/world (core/src/test/fixtures/worlds by default),
# and the commands with their times go to build/fixtures/<Name>/make-log.txt for the fixture note.
# view-distance is above simulation-distance, so some chunks are generated and sent to the
# player without ever being simulated (InhabitedTime 0).

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'vanilla-server.ps1')

if (-not $Name) { $Name = $Minecraft }
if ($Compression -and $Minecraft -eq '1.20.1') { throw 'region-file-compression does not exist on 1.20.1.' }
$settings = Get-FixtureVersion $Minecraft
$javaExe = Get-JavaExe -Version $settings.Java
$fixtureRoot = Join-Path $ProjectRoot 'build/fixtures'
$serverDir = Join-Path $fixtureRoot $Name
if (-not $OutputRoot) { $OutputRoot = Join-Path $ProjectRoot 'core/src/test/fixtures/worlds' }
$target = Join-Path (Join-Path $OutputRoot $Name) 'world'
if ((Test-Path -LiteralPath $target) -and -not $Force) { throw "$target exists. Use -Force to replace it." }

$serverJar = Install-VanillaServer -Minecraft $Minecraft -CacheRoot (Join-Path $fixtureRoot 'servers')
if (Test-Path -LiteralPath $serverDir) { Remove-Item -LiteralPath $serverDir -Recurse -Force }
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null

$rconPassword = [Guid]::NewGuid().ToString('N')
$properties = @(
    'online-mode=false'
    'white-list=false'
    "server-port=$ServerPort"
    'enable-rcon=true'
    "rcon.port=$RconPort"
    "rcon.password=$rconPassword"
    "level-seed=$Seed"
    'level-name=world'
    "view-distance=$ViewDistance"
    "simulation-distance=$SimulationDistance"
    'gamemode=creative'
    'difficulty=peaceful'
    'spawn-protection=0'
)
if ($Compression) { $properties += "region-file-compression=$Compression" }
if ($LevelType -eq 'flat') { $properties += 'level-type=minecraft\:flat' }
Write-VanillaServerConfig -ServerDir $serverDir -Properties $properties

# Entity data as each version writes it in commands (checked in the decompiled sources).
$keepSyntax = @{
    '1.20.1' = @{ PackMeta = '{"pack":{"pack_format":15,"description":"Fixture dimension"}}'; Name = 'CustomName:''"Bacon"'''; Item = 'Item:{id:"minecraft:diamond",Count:1b}'; Boat = 'boat'; Leash = { param($x, $y, $z) "Leash:{X:$x,Y:$y,Z:$z}" }; LeashTo = 'Leash'; Elytra = 'Item:{id:"minecraft:elytra",Count:1b}' }
    '1.21.1' = @{ PackMeta = '{"pack":{"pack_format":48,"description":"Fixture dimension"}}'; Name = 'CustomName:''"Bacon"'''; Item = 'Item:{id:"minecraft:diamond",count:1}'; Boat = 'boat'; Leash = { param($x, $y, $z) "leash:[I;$x,$y,$z]" }; LeashTo = 'leash'; Elytra = 'Item:{id:"minecraft:elytra",count:1}' }
    '26.3' = @{ PackMeta = '{"pack":{"min_format":121,"max_format":121,"description":"Fixture dimension"}}'; Name = 'CustomName:"Bacon"'; Item = 'Item:{id:"minecraft:diamond",count:1}'; Boat = 'oak_boat'; Leash = { param($x, $y, $z) "leash:[I;$x,$y,$z]" }; LeashTo = 'leash'; Elytra = 'Item:{id:"minecraft:elytra",count:1}' }
}
if ($Plan -eq 'keep') {
    if ($LevelType -ne 'flat') { throw 'The keep plan needs the superflat world (its village and heights are known).' }
    # A datapack dimension, read by the server when it creates the world (S8).
    $pack = Join-Path $serverDir 'world/datapacks/examplemod'
    New-Item -ItemType Directory -Path (Join-Path $pack 'data/examplemod/dimension') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $pack 'pack.mcmeta') -Value $keepSyntax[$Minecraft].PackMeta -Encoding ascii
    $dimension = '{"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3}],"lakes":false,"features":false}}}'
    Set-Content -LiteralPath (Join-Path $pack 'data/examplemod/dimension/mining.json') -Value $dimension -Encoding ascii
}

function Invoke-RconExpect {
    # Sends a command and fails unless the answer matches the pattern.
    param([string]$Command, [string]$Pattern)
    $answer = Invoke-Rcon -Port $RconPort -Password $rconPassword -Command $Command
    if ($answer -notmatch $Pattern) { throw "RCON '$Command' answered '$answer', expected /$Pattern/." }
    return $answer
}

$botName = 'Explorer'
$script:rconLog = @()
$timeline = @()
$started = Get-Date
$server = Start-VanillaServer -ServerDir $serverDir -JavaExe $javaExe -ServerJar $serverJar
$bot = $null
try {
    Wait-ServerLog -ServerDir $serverDir -Process $server -Pattern 'Done \([^)]+\)! For help' -Seconds 300
    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command 'list' -Retries 30 | Out-Null
    $timeline += "server ready after $([int]((Get-Date) - $started).TotalSeconds) s"

    Install-JoinBotClasses -JavaExe $javaExe -Destination (Join-Path $serverDir 'join-bot')
    $offset = (Get-ServerLogText $serverDir).Length
    $bot = Start-JoinBotProcess -JavaExe $javaExe -ClassDir 'join-bot' -WorkDir $serverDir -Port $ServerPort `
        -Protocol $settings.Protocol -Name $botName -StaySeconds 3600 -ViewDistance $ViewDistance
    Wait-ServerLog -ServerDir $serverDir -Process $server -Pattern "$botName\[[^\]]*\] logged in" -Seconds 120 -Offset $offset
    $joinedAt = Get-Date
    Start-Sleep -Seconds 2
    if ((Get-JoinBotLog $serverDir $botName) -notmatch 'at (-?[0-9.]+) (-?[0-9.]+) (-?[0-9.]+)') {
        throw "The join bot reported no position: $(Get-JoinBotLog $serverDir $botName)"
    }
    $originX = [int][Math]::Floor([double]$Matches[1])
    $originZ = [int][Math]::Floor([double]$Matches[3])
    $timeline += "joined at x=$originX z=$originZ (the world spawn area)"

    if ($Plan -eq 'spawn') {
        Start-Sleep -Seconds 10
        $timeline += 'stayed 10 s at the join position'
    } elseif ($Plan -eq 'keep') {
        # Offline players get UUID.nameUUIDFromBytes("OfflinePlayer:<name>"): MD5, version 3.
        $md5 = [System.Security.Cryptography.MD5]::Create()
        $hash = $md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$botName"))
        $md5.Dispose()
        $hash[6] = ($hash[6] -band 0x0f) -bor 0x30
        $hash[8] = ($hash[8] -band 0x3f) -bor 0x80
        $ownerInts = @(0, 4, 8, 12 | ForEach-Object {
                [BitConverter]::ToInt32([byte[]]@($hash[$_ + 3], $hash[$_ + 2], $hash[$_ + 1], $hash[$_]), 0)
            }) -join ','
        $syntax = $keepSyntax[$Minecraft]
        Start-Sleep -Seconds 5
        $timeline += 'stayed 5 s at the join position'
        # S4: the plains village that starts in chunk (0, 14) has a street in chunk (5, 14), five
        # chunks east (same layout on the three versions with this seed). The player stands in
        # chunk (8, 14), so the start chunk stays beyond the view distance and is never simulated.
        Invoke-RconExpect -Command 'locate structure minecraft:village_plains' -Pattern '\[(-?\d+), ~, (-?\d+)\]' | Out-Null
        Invoke-RconExpect -Command "tp $botName 136 -50 232" -Pattern 'Teleported' | Out-Null
        Start-Sleep -Seconds 15
        Invoke-RconExpect -Command 'fill 86 -55 230 90 -55 234 minecraft:glass' -Pattern 'Successfully filled' | Out-Null
        Start-Sleep -Seconds 2
        $timeline += 'village: stood in chunk (8, 14) at x=136 z=232 for 15 s, then a 5 by 5 glass fill at y=-55 in chunk (5, 14), a street of the village that starts in chunk (0, 14)'
        # K3 and K5: one setup per chunk along chunk row z=-10 (block z=-152), chunks x=-7 to 6,
        # loaded with forceload while the player is 24 chunks away, so none of them is simulated
        # near a player. NoAI keeps each mob in its chunk.
        Invoke-RconExpect -Command 'forceload add -112 -160 111 -145' -Pattern 'Marked|marked' | Out-Null
        Start-Sleep -Seconds 3
        $z = -152
        $setups = @(
            , @(-7, "summon wolf {x} -60 $z {NoAI:1b,Sitting:1b,Owner:[I;$ownerInts]}")
            , @(-6, "summon pig {x} -60 $z {NoAI:1b,$($syntax.Name)}")
            , @(-5, "summon sheep {x1} -60 $z {NoAI:1b,$(& $syntax.Leash '{x}' -60 $z)}")
            , @(-4, "summon cow {x} -60 $z {NoAI:1b,PersistenceRequired:1b}")
            , @(-3, "summon armor_stand {x} -60 $z")
            , @(-2, "summon item_frame {x} -60 $z {Facing:1b,$($syntax.Item)}")
            , @(-1, "summon minecart {x} -60 $z")
            , @(0, "summon $($syntax.Boat) {x} -60 $z")
            , @(1, "summon end_crystal {x} -60 $z {ShowBottom:0b}")
            , @(2, "summon item {x} -60 $z {Age:-32768s,$($syntax.Item)}")
            , @(3, "summon chest_minecart {x} -60 $z {LootTable:`"minecraft:chests/abandoned_mineshaft`"}")
            , @(4, "summon end_crystal {x} -60 $z {ShowBottom:1b}")
            , @(5, "summon cow {x} -60 $z {NoAI:1b}")
        )
        foreach ($setup in $setups) {
            $cx = $setup[0]
            $x = 16 * $cx + 8
            if ($cx -eq -5) {
                Invoke-RconExpect -Command "setblock $x -60 $z minecraft:oak_fence" -Pattern 'Changed the block' | Out-Null
            }
            $command = $setup[1] -replace '\{x1\}', ($x + 2) -replace '\{x\}', $x
            Invoke-RconExpect -Command $command -Pattern 'Summoned new' | Out-Null
            $timeline += "chunk ($cx, -10): $command"
        }
        # K5 player marks, passengers and the wandering trader's llama: chunk row z=-12, x=-7 to 1.
        Invoke-RconExpect -Command 'forceload add -112 -192 31 -177' -Pattern 'Marked|marked' | Out-Null
        Start-Sleep -Seconds 3
        $z2 = -184
        $marks = @(
            , @(-7, "summon fox {x} -60 $z2 {NoAI:1b,Trusted:[[I;$ownerInts]]}")
            , @(-6, "summon allay {x} -60 $z2 {NoAI:1b,Brain:{memories:{`"minecraft:liked_player`":{value:[I;$ownerInts]}}}}")
            , @(-5, "summon axolotl {x} -60 $z2 {NoAI:1b,FromBucket:1b}")
            , @(-4, "summon iron_golem {x} -60 $z2 {NoAI:1b,PlayerCreated:1b}")
            , @(-3, "summon iron_golem {x} -60 $z2 {NoAI:1b}")
            , @(-2, "summon horse {x} -60 $z2 {NoAI:1b,Passengers:[{id:`"minecraft:pig`",NoAI:1b,$($syntax.Name)}]}")
            , @(-1, "summon horse {x} -60 $z2 {NoAI:1b,Passengers:[{id:`"minecraft:pig`",NoAI:1b}]}")
            , @(0, "summon wandering_trader {x} -60 $z2 {NoAI:1b,DespawnDelay:48000,UUID:[I;1,2,3,4]}")
            , @(0, "summon trader_llama {x1} -60 $z2 {NoAI:1b,$($syntax.LeashTo):{UUID:[I;1,2,3,4]}}")
            , @(1, "summon trader_llama {x1} -60 $z2 {NoAI:1b,$(& $syntax.Leash '{x}' -60 $z2)}")
        )
        foreach ($setup in $marks) {
            $cx = $setup[0]
            $x = 16 * $cx + 8
            if ($cx -eq 1) {
                Invoke-RconExpect -Command "setblock $x -60 $z2 minecraft:oak_fence" -Pattern 'Changed the block' | Out-Null
            }
            $command = $setup[1] -replace '\{x1\}', ($x + 2) -replace '\{x\}', $x
            Invoke-RconExpect -Command $command -Pattern 'Summoned new' | Out-Null
            $timeline += "chunk ($cx, -12): $command"
        }
        Start-Sleep -Seconds 5
        Invoke-RconExpect -Command 'forceload remove -112 -192 31 -177' -Pattern 'Unmarked|unmarked' | Out-Null
        $timeline += 'removed the forceload of chunks x=-7 to 1, z=-12'
        # The End: the main island generated by a forceload, so the game places the end spike
        # crystals itself, and chunk row z=20 with two crystals and an item frame on obsidian.
        Invoke-RconExpect -Command 'execute in minecraft:the_end run forceload add -48 -48 47 47' -Pattern 'Marked|marked' | Out-Null
        Start-Sleep -Seconds 10
        Invoke-RconExpect -Command 'execute in minecraft:the_end run forceload add 0 320 63 335' -Pattern 'Marked|marked' | Out-Null
        Start-Sleep -Seconds 3
        $end = @(
            , @(0, "summon end_crystal 8 65 328 {ShowBottom:1b}")
            , @(1, "summon end_crystal 24 65 328 {ShowBottom:0b}")
            , @(2, "summon item_frame 40 65 328 {Facing:1b,$($syntax.Elytra)}")
        )
        foreach ($setup in $end) {
            $x = 16 * $setup[0] + 8
            Invoke-RconExpect -Command "execute in minecraft:the_end run setblock $x 64 328 minecraft:obsidian" -Pattern 'Changed the block' | Out-Null
            Invoke-RconExpect -Command "execute in minecraft:the_end run $($setup[1])" -Pattern 'Summoned new' | Out-Null
            $timeline += "the End, chunk ($($setup[0]), 20): obsidian at y=64, then $($setup[1])"
        }
        Start-Sleep -Seconds 5
        Invoke-RconExpect -Command 'execute in minecraft:the_end run forceload remove all' -Pattern 'Unmarked|unmarked|Removed|removed' | Out-Null
        $timeline += 'the End: chunks -3 to 2 (main island) force-loaded for 18 s and chunks x=0 to 3, z=20 for 8 s, then released'
        Invoke-RconExpect -Command 'forceload remove -112 -160 111 -145' -Pattern 'Unmarked|unmarked' | Out-Null
        Invoke-RconExpect -Command "forceload add -136 $z" -Pattern 'Marked|marked' | Out-Null
        $timeline += 'removed the forceload of chunks x=-7 to 6, z=-10; forceload kept on chunk (-9, -10) (K3)'
        Start-Sleep -Seconds 3
        # S8: chunks generated in the datapack dimension by a short forceload.
        Invoke-RconExpect -Command 'execute in examplemod:mining run forceload add 8 8' -Pattern 'Marked|marked' | Out-Null
        Start-Sleep -Seconds 5
        Invoke-RconExpect -Command 'execute in examplemod:mining run forceload remove 8 8' -Pattern 'Unmarked|unmarked' | Out-Null
        $timeline += 'examplemod:mining: chunk (0, 0) force-loaded for 5 s, then released'
        Start-Sleep -Seconds 3
    } else {
        Start-Sleep -Seconds 5
        $timeline += 'stayed 5 s at the join position'
        # Explored strip: four stops one chunk apart, 400 blocks east of the join position.
        for ($k = 0; $k -lt 4; $k++) {
            $x = $originX + 400 + 16 * $k
            Invoke-Rcon -Port $RconPort -Password $rconPassword -Command "tp $botName $x 100 $originZ" | Out-Null
            Start-Sleep -Seconds 15
            # Stands for the blocks a player places: the game only saves InhabitedTime again
            # when the chunk's region data changes (MC-127407).
            $fill = "fill $($x - 2) 99 $($originZ - 2) $($x + 2) 99 $($originZ + 2) minecraft:glass"
            # 'Successfully filled' also proves that the server loaded the teleport destination.
            Invoke-RconExpect -Command $fill -Pattern 'Successfully filled' | Out-Null
            Start-Sleep -Seconds 2
            $timeline += "explored strip: stop $($k + 1) at x=$x z=$originZ, 15 s, then a 5 by 5 glass fill under the player, 2 s"
        }
        # Flown-over area: seven stops two chunks apart, 400 blocks south, about 1 s each.
        $flyZ = $originZ + 400
        for ($k = 0; $k -lt 7; $k++) {
            $x = $originX - 96 + 32 * $k
            Invoke-Rcon -Port $RconPort -Password $rconPassword -Command "tp $botName $x 140 $flyZ" | Out-Null
            Start-Sleep -Seconds 1
            $timeline += "flown-over area: stop $($k + 1) at x=$x z=$flyZ, 1 s"
        }
        # A respawn point in the flown-over area, so player data has one away from the strip.
        $respawnX = $originX - 96 + 32 * 3
        Invoke-Rcon -Port $RconPort -Password $rconPassword -Command "spawnpoint $botName $respawnX 100 $flyZ" | Out-Null
        $timeline += "respawn point set at x=$respawnX z=$flyZ"
        # Back in the strip, so the last saved position is inside it.
        $endX = $originX + 448
        Invoke-Rcon -Port $RconPort -Password $rconPassword -Command "tp $botName $endX 100 $originZ" | Out-Null
        Start-Sleep -Seconds 5
        $timeline += "back in the strip at x=$endX z=$originZ, 5 s"
    }

    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command "kick $botName Fixture done" | Out-Null
    Wait-ServerLog -ServerDir $serverDir -Process $server -Pattern "$botName left the game" -Seconds 60 -Offset $offset
    Assert-JoinBotKicked -Process $bot -WorkDir $serverDir -Name $botName -Reason 'Fixture done'
    $timeline += "kicked after $([int]((Get-Date) - $joinedAt).TotalSeconds) s online"
    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command 'save-all flush' | Out-Null
    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command 'stop' | Out-Null
    if (-not $server.WaitForExit(120000)) { throw 'The server did not stop within 2 minutes.' }
} finally {
    if ($bot) { Stop-ProcessTree $bot }
    Stop-ProcessTree $server
}

$log = Get-ServerLogText $serverDir
if ($log -notmatch 'Stopping (the )?server') { throw 'The server did not log a clean shutdown.' }

if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $serverDir 'world') -Destination $target -Recurse

$lines = @(
    "Minecraft $Minecraft, vanilla dedicated server $serverJar (SHA-1 $(Get-FileSha1 $serverJar))"
    "Java: $javaExe"
    "Properties: $(($properties | Where-Object { $_ -notmatch '^rcon\.password=' }) -join ', ')"
    "Plan: $Plan, bot name $botName, protocol $($settings.Protocol), client view distance $ViewDistance"
    ''
    'Timeline:'
) + ($timeline | ForEach-Object { "  $_" }) + @('', 'RCON commands (UTC time, command, answer):') +
    ($script:rconLog | ForEach-Object { "  $($_.Time)  $($_.Command)  ->  $($_.Answer)" })
Set-Content -LiteralPath (Join-Path $serverDir 'make-log.txt') -Value $lines -Encoding utf8
$size = (Get-ChildItem -LiteralPath $target -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host "Fixture world $Name written to $target ($size bytes). Log: $(Join-Path $serverDir 'make-log.txt')" -ForegroundColor Green
