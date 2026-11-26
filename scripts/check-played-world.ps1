[CmdletBinding()]
param(
    # The release JAR whose command line prunes (a 1.21.1 band JAR, run with Java 21).
    [Parameter(Mandatory)]
    [string]$Jar,
    [string]$Launcher,
    [string]$ClientRoot,
    [long]$Seed = 20261126,
    [int]$ServerPort = 25606,
    [int]$RconPort = 25656
)

# A world played with the real client, pruned with the command line, then opened again in the real
# client (docs/TESTING.md, Played world). On a vanilla 1.21.1 dedicated server, the real client
# (player "Player") and the headless join client ("Builder") join; RCON builds a glass room with a
# chest, two named animals, an armor stand and an item frame where the real client stands, a stone
# tower where the join client stands, and flies the real client over a line of chunks. Then:
#   1. dry run and prune of a copy with `java -jar <Jar>`;
#   2. that copy opened on the vanilla server with the real client: every build, the chest items
#      and every entity are still there, and a flown-over chunk that was pruned generates again;
#   3. restore.txt followed on a second pruned copy: the world is byte for byte as before the prune;
#   4. the session lock: with a copy open in the real client in singleplayer, the command line
#      refuses with exit code 3, writes nothing and leaves the region files as they were.
# The client opens windows on the desktop and closes them when its part is done. The work folders
# are in build/played-world/. Works on Windows PowerShell 5.1 and on PowerShell 7; checked so far
# on Linux with Hyprland.

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'vanilla-server.ps1')
. (Join-Path $PSScriptRoot 'real-client.ps1')

$Minecraft = '1.21.1'
$settings = Get-FixtureVersion $Minecraft
$javaExe = Get-JavaExe -Version $settings.Java
$launcherPath = Get-PortableMc -Launcher $Launcher
$clientRoot = Get-ClientRoot -ClientRoot $ClientRoot
$work = Join-Path $ProjectRoot 'build/played-world'
$serverDir = Join-Path $work 'server'
$serverJar = Install-VanillaServer -Minecraft $Minecraft -CacheRoot (Join-Path $ProjectRoot 'build/fixtures/servers')
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
$rconPassword = [Guid]::NewGuid().ToString('N')
$script:rconLog = @()

function Write-ServerFiles {
    Write-VanillaServerConfig -ServerDir $serverDir -Properties @(
        'online-mode=false', 'white-list=false', "server-port=$ServerPort", 'enable-rcon=true', "rcon.port=$RconPort",
        "rcon.password=$rconPassword", "level-seed=$Seed", 'level-name=world', 'view-distance=6', 'simulation-distance=4',
        'gamemode=creative', 'difficulty=peaceful', 'spawn-protection=0', 'allow-flight=true')
}

function Send-Rcon {
    param([string]$Command, [string]$Pattern)
    $answer = Invoke-Rcon -Port $RconPort -Password $rconPassword -Command $Command -Retries 10
    if ($Pattern -and $answer -notmatch $Pattern) { throw "RCON '$Command' answered '$answer', expected /$Pattern/." }
    return $answer
}

function Start-Server {
    Remove-Item -LiteralPath (Join-Path $serverDir 'logs/latest.log') -ErrorAction SilentlyContinue
    $process = Start-VanillaServer -ServerDir $serverDir -JavaExe $javaExe -ServerJar $serverJar
    Wait-ServerLog -ServerDir $serverDir -Process $process -Pattern 'Done \([^)]+\)! For help' -Seconds 300
    Send-Rcon -Command 'list' | Out-Null
    return $process
}

function Stop-Server {
    param([System.Diagnostics.Process]$Process)
    Send-Rcon -Command 'save-all flush' | Out-Null
    Send-Rcon -Command 'stop' | Out-Null
    if (-not $Process.WaitForExit(120000)) { throw 'The server did not stop within 2 minutes.' }
}

function Start-Player {
    param([string]$GameDir, [string]$World)
    return Start-RealClient -Launcher $launcherPath -Root $clientRoot -Version $Minecraft -JavaExe $javaExe -Name 'Player' `
        -GameDir $GameDir -Port $ServerPort -World $World
}

function Wait-PlayerJoined {
    param([System.Diagnostics.Process]$Server, [int]$Offset)
    Wait-ServerLog -ServerDir $serverDir -Process $Server -Pattern 'Player\[[^\]]*\] logged in' -Seconds 300 -Offset $Offset
    Start-Sleep -Seconds 5
}

function Test-ChunkPresent {
    # Whether the region file of the world's overworld has an entry for the chunk.
    param([string]$World, [int]$ChunkX, [int]$ChunkZ)
    $rx = [int][Math]::Floor($ChunkX / 32)
    $rz = [int][Math]::Floor($ChunkZ / 32)
    $file = Join-Path $World "region/r.$rx.$rz.mca"
    if (-not (Test-Path -LiteralPath $file)) { return $false }
    $index = (($ChunkX % 32 + 32) % 32) + 32 * (($ChunkZ % 32 + 32) % 32)
    $stream = [System.IO.File]::OpenRead($file)
    try {
        if ($stream.Length -lt 4096) { return $false }
        $bytes = New-Object byte[] 4
        $stream.Position = 4 * $index
        $stream.Read($bytes, 0, 4) | Out-Null
    } finally { $stream.Dispose() }
    return ($bytes[0] -ne 0 -or $bytes[1] -ne 0 -or $bytes[2] -ne 0 -or $bytes[3] -ne 0)
}

function Get-StorageHashes {
    # SHA-256 of every region, entities and poi file of every dimension, by path in the world.
    param([string]$World)
    $hashes = @{}
    Get-ChildItem -LiteralPath $World -Recurse -File | Where-Object {
        $_.Directory.Name -in @('region', 'entities', 'poi') -and $_.FullName -notmatch '[\\/]chunkpruner[\\/]'
    } | ForEach-Object {
        $relative = $_.FullName.Substring($World.Length).TrimStart('\', '/').Replace('\', '/')
        $hashes[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }
    return $hashes
}

function Compare-Hashes {
    param([hashtable]$Expected, [hashtable]$Actual, [string]$What)
    $diff = @()
    foreach ($key in $Expected.Keys) { if ($Actual[$key] -ne $Expected[$key]) { $diff += $key } }
    foreach ($key in $Actual.Keys) { if (-not $Expected.ContainsKey($key)) { $diff += "$key (new)" } }
    if ($diff.Count -gt 0) { throw "$What`: $($diff.Count) files differ: $(($diff | Select-Object -First 5) -join ', ')" }
}

function Invoke-Cli {
    param([string[]]$Arguments)
    $result = Invoke-NativeCapture -FilePath $javaExe -Arguments (@('-jar', $Jar) + $Arguments)
    Write-Host "  java -jar $(Split-Path -Leaf $Jar) $($Arguments -join ' ') -> exit $($result.Code)"
    return $result
}

$results = @()

# --- The played world -------------------------------------------------------------------------
Write-ServerFiles
$server = Start-Server
$client = $null
$bot = $null
try {
    $offset = (Get-ServerLogText $serverDir).Length
    $client = Start-Player -GameDir (Join-Path $work 'client-play')
    Wait-PlayerJoined -Server $server -Offset $offset
    $pos = Send-Rcon -Command 'data get entity Player Pos' -Pattern '\['
    if ($pos -notmatch '\[(-?[0-9.]+)d, (-?[0-9.]+)d, (-?[0-9.]+)d\]') { throw "No position in '$pos'." }
    $ox = [int][Math]::Floor([double]$Matches[1]) + 64
    $oz = [int][Math]::Floor([double]$Matches[3]) + 64
    $y = 150
    # A glass room on a stone floor, a chest with items, two named animals, an armor stand with a
    # helmet and an item frame on the wall, where the real client stands for 40 s.
    Send-Rcon -Command "fill $($ox - 4) $($y - 1) $($oz - 4) $($ox + 4) $($y - 1) $($oz + 4) minecraft:stone" -Pattern 'Successfully filled' | Out-Null
    Send-Rcon -Command "fill $($ox - 4) $y $($oz - 4) $($ox + 4) $($y + 2) $($oz + 4) minecraft:glass hollow" -Pattern 'Successfully filled' | Out-Null
    Send-Rcon -Command "fill $($ox - 3) $y $($oz - 3) $($ox + 3) $($y + 2) $($oz + 3) minecraft:air" -Pattern 'Successfully filled' | Out-Null
    Send-Rcon -Command "tp Player $ox $y $oz" -Pattern 'Teleported' | Out-Null
    Start-Sleep -Seconds 3
    Send-Rcon -Command "setblock $($ox + 2) $y $($oz + 2) minecraft:chest{Items:[{Slot:0b,id:`"minecraft:diamond`",count:7},{Slot:1b,id:`"minecraft:golden_apple`",count:2}]}" -Pattern 'Changed the block' | Out-Null
    Send-Rcon -Command "summon cow $($ox - 2) $y $($oz - 2) {CustomName:'`"Daisy`"',NoAI:1b}" -Pattern 'Summoned' | Out-Null
    Send-Rcon -Command "summon pig $($ox - 2) $y $($oz + 2) {CustomName:'`"Bacon`"',NoAI:1b}" -Pattern 'Summoned' | Out-Null
    Send-Rcon -Command "summon armor_stand $($ox + 2) $y $($oz - 2) {ArmorItems:[{},{},{},{id:`"minecraft:diamond_helmet`",count:1}]}" -Pattern 'Summoned' | Out-Null
    Send-Rcon -Command "summon item_frame $($ox + 3) $($y + 1) $oz {Facing:4b,Item:{id:`"minecraft:emerald`",count:1}}" -Pattern 'Summoned' | Out-Null
    Start-Sleep -Seconds 40

    # The join client builds a stone tower 400 blocks east and stands there for 20 s.
    Install-JoinBotClasses -JavaExe $javaExe -Destination (Join-Path $serverDir 'join-bot')
    $botOffset = (Get-ServerLogText $serverDir).Length
    $bot = Start-JoinBotProcess -JavaExe $javaExe -ClassDir 'join-bot' -WorkDir $serverDir -Port $ServerPort `
        -Protocol $settings.Protocol -Name 'Builder' -StaySeconds 3600 -ViewDistance 6
    Wait-ServerLog -ServerDir $serverDir -Process $server -Pattern 'Builder\[[^\]]*\] logged in' -Seconds 120 -Offset $botOffset
    $tx = $ox + 400
    Send-Rcon -Command "tp Builder $tx $($y + 12) $oz" -Pattern 'Teleported' | Out-Null
    Start-Sleep -Seconds 15
    Send-Rcon -Command "fill $tx $($y - 10) $oz $($tx + 2) $($y + 10) $($oz + 2) minecraft:cobblestone" -Pattern 'Successfully filled' | Out-Null
    Start-Sleep -Seconds 20

    # The real client flies over a line of chunks 600 blocks south, one second per stop.
    $flyZ = $oz + 600
    for ($k = 0; $k -lt 8; $k++) {
        Send-Rcon -Command "tp Player $($ox - 192 + 48 * $k) 220 $flyZ" -Pattern 'Teleported' | Out-Null
        Start-Sleep -Seconds 1
    }
    Send-Rcon -Command "tp Player $ox $y $oz" -Pattern 'Teleported' | Out-Null
    Start-Sleep -Seconds 10
    $problems = Test-RealClientHealthy -Client $client
    if ($problems.Count -gt 0) { throw "The real client: $($problems -join '; ')" }
    Close-RealClient -Client $client | Out-Null
    $client = $null
    Send-Rcon -Command 'kick Builder Played world done' -Pattern 'Kicked' | Out-Null
    Assert-JoinBotKicked -Process $bot -WorkDir $serverDir -Name 'Builder' -Reason 'Played world done'
    Stop-Server -Process $server
} finally {
    if ($client) { Stop-RealClientProcess -Client $client }
    if ($bot) { Stop-ProcessTree $bot }
    Stop-ProcessTree $server
}
$played = Join-Path $work 'played'
Copy-Item -LiteralPath (Join-Path $serverDir 'world') -Destination $played -Recurse
$beforePrune = Get-StorageHashes -World $played
$base = @([int][Math]::Floor($ox / 16), [int][Math]::Floor($oz / 16))
$tower = @([int][Math]::Floor($tx / 16), [int][Math]::Floor($oz / 16))
Write-Host "Played world: room in chunk $($base -join ', '), tower in chunk $($tower -join ', '), flown over at z=$flyZ"

# --- Dry run and prune with the command line --------------------------------------------------
$pruned = Join-Path $work 'pruned'
Copy-Item -LiteralPath $played -Destination $pruned -Recurse
$dry = Invoke-Cli -Arguments @('--world', $pruned, '--dry-run')
if ($dry.Code -ne 0) { throw "The dry run failed: $($dry.Output)" }
Compare-Hashes -Expected $beforePrune -Actual (Get-StorageHashes -World $pruned) -What 'after the dry run'
$prune = Invoke-Cli -Arguments @('--world', $pruned)
if ($prune.Code -ne 0) { throw "The prune failed: $($prune.Output)" }
$summary = ($prune.Output -split "`r?`n" | Where-Object { $_ -match '^Removed ' } | Select-Object -First 1)
if (-not $summary) { throw "The prune removed nothing: $($prune.Output)" }
Write-Host "  $summary"
foreach ($chunk in @($base, $tower)) {
    if (-not (Test-ChunkPresent -World $pruned -ChunkX $chunk[0] -ChunkZ $chunk[1])) { throw "The prune removed played chunk $($chunk -join ', ')." }
}
$flyChunkZ = [int][Math]::Floor($flyZ / 16)
$gone = @()
for ($cx = [int][Math]::Floor(($ox - 192) / 16); $cx -le [int][Math]::Floor(($ox + 144) / 16); $cx++) {
    foreach ($cz in @(($flyChunkZ - 6), ($flyChunkZ + 6))) {
        if ((Test-ChunkPresent -World $played -ChunkX $cx -ChunkZ $cz) -and -not (Test-ChunkPresent -World $pruned -ChunkX $cx -ChunkZ $cz)) { $gone += , @($cx, $cz) }
    }
}
if ($gone.Count -eq 0) { throw 'No chunk at the edge of the flown-over area was pruned.' }
$target = $gone[[int]($gone.Count / 2)]
Write-Host "  $($gone.Count) chunks at the edges of the flown-over area were pruned; chunk $($target -join ', ') is checked after reopening"
$results += "prune: $summary"
$restoreCopy = Join-Path $work 'restored'
Copy-Item -LiteralPath $pruned -Destination $restoreCopy -Recurse

# --- The pruned world opened in the real client -----------------------------------------------
Remove-Item -LiteralPath (Join-Path $serverDir 'world') -Recurse -Force
Copy-Item -LiteralPath $pruned -Destination (Join-Path $serverDir 'world') -Recurse
$server = Start-Server
$client = $null
try {
    $offset = (Get-ServerLogText $serverDir).Length
    $client = Start-Player -GameDir (Join-Path $work 'client-reopen')
    Wait-PlayerJoined -Server $server -Offset $offset
    Send-Rcon -Command "tp Player $ox $y $oz" -Pattern 'Teleported' | Out-Null
    Start-Sleep -Seconds 10
    Send-Rcon -Command "execute if block $ox $($y - 1) $oz minecraft:stone" -Pattern 'Test passed' | Out-Null
    Send-Rcon -Command "execute if block $($ox + 4) $($y + 1) $oz minecraft:glass" -Pattern 'Test passed' | Out-Null
    Send-Rcon -Command "data get block $($ox + 2) $y $($oz + 2) Items" -Pattern 'minecraft:diamond.*count: 7|count: 7.*minecraft:diamond' | Out-Null
    Send-Rcon -Command 'execute if entity @e[type=cow,name=Daisy]' -Pattern 'Test passed' | Out-Null
    Send-Rcon -Command 'execute if entity @e[type=pig,name=Bacon]' -Pattern 'Test passed' | Out-Null
    Send-Rcon -Command 'data get entity @e[type=armor_stand,limit=1] ArmorItems' -Pattern 'minecraft:diamond_helmet' | Out-Null
    Send-Rcon -Command 'data get entity @e[type=item_frame,limit=1] Item' -Pattern 'minecraft:emerald' | Out-Null
    Send-Rcon -Command "forceload add $tx $oz" -Pattern 'Marked' | Out-Null
    Send-Rcon -Command "execute if block $tx $y $oz minecraft:cobblestone" -Pattern 'Test passed' | Out-Null
    Send-Rcon -Command "forceload remove $tx $oz" | Out-Null
    $results += 'reopened: the room, the chest items, Daisy, Bacon, the armor stand helmet, the item frame and the tower are there'
    Send-Rcon -Command "tp Player $($target[0] * 16 + 8) 220 $($target[1] * 16 + 8)" -Pattern 'Teleported' | Out-Null
    Start-Sleep -Seconds 20
    $problems = Test-RealClientHealthy -Client $client
    if ($problems.Count -gt 0) { throw "The real client: $($problems -join '; ')" }
    Close-RealClient -Client $client | Out-Null
    $client = $null
    Stop-Server -Process $server
} finally {
    if ($client) { Stop-RealClientProcess -Client $client }
    Stop-ProcessTree $server
}
$log = Get-ServerLogText $serverDir
$regionProblems = @($log -split "`r?`n" | Where-Object { $_ -match "Couldn't load chunk|invalid sector|is truncated|stream is missing|invalid chunk stream|wrong location" })
if ($regionProblems.Count -gt 0) { throw "The reopened world logged region problems:`n$($regionProblems -join "`n")" }
if (-not (Test-ChunkPresent -World (Join-Path $serverDir 'world') -ChunkX $target[0] -ChunkZ $target[1])) { throw "The pruned chunk $($target -join ', ') did not generate again." }
$results += "flown over: pruned chunk $($target -join ', ') generated again when the real client flew there"

# --- restore.txt --------------------------------------------------------------------------------
$restoreFile = Get-ChildItem -LiteralPath (Join-Path $restoreCopy 'chunkpruner/backups') -Recurse -Filter 'restore.txt' -File | Select-Object -First 1
if (-not $restoreFile) { throw 'The prune left no restore.txt.' }
$steps = 0
foreach ($line in (Get-Content -LiteralPath $restoreFile.FullName)) {
    if ($line -match '^Copy the files of (.+) into (.+)$') {
        $from = Join-Path $restoreCopy $Matches[1]
        $to = Join-Path $restoreCopy $Matches[2]
        New-Item -ItemType Directory -Path $to -Force | Out-Null
        Get-ChildItem -LiteralPath $from -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $to $_.Name) -Force }
        $steps++
    }
}
if ($steps -eq 0) { throw "restore.txt has no copy step: $(Get-Content -LiteralPath $restoreFile.FullName -Raw)" }
Compare-Hashes -Expected $beforePrune -Actual (Get-StorageHashes -World $restoreCopy) -What 'after following restore.txt'
$results += "restore.txt: $steps copy steps, every region, entities and poi file is back as before the prune"

# --- The session lock with the world open in the real client ------------------------------------
$gameDir = Join-Path $work 'client-lock'
$saves = Join-Path $gameDir 'saves'
New-Item -ItemType Directory -Path $saves -Force | Out-Null
$lockWorld = Join-Path $saves 'LockTest'
Copy-Item -LiteralPath $played -Destination $lockWorld -Recurse
$client = $null
try {
    $client = Start-Player -GameDir $gameDir -World 'LockTest'
    if (-not (Wait-RealClientLog -Client $client -Pattern 'Player joined the game|Player\[local\] logged in' -Seconds 300)) { throw 'The real client did not open the singleplayer world.' }
    Start-Sleep -Seconds 5
    $before = Get-StorageHashes -World $lockWorld
    foreach ($arguments in @(@('--world', $lockWorld), @('--world', $lockWorld, '--dry-run'))) {
        $refused = Invoke-Cli -Arguments $arguments
        if ($refused.Code -ne 3) { throw "With the world open, the command line answered exit code $($refused.Code): $($refused.Output)" }
        Write-Host "  $(($refused.Output -split "`r?`n" | Where-Object { $_ } | Select-Object -First 1))"
    }
    if (Test-Path -LiteralPath (Join-Path $lockWorld 'chunkpruner')) { throw 'The refused run wrote a chunkpruner folder.' }
    Compare-Hashes -Expected $before -Actual (Get-StorageHashes -World $lockWorld) -What 'with the world open'
    $results += 'session lock: with the world open in the real client, prune and dry run exit with code 3 and write nothing'
    Close-RealClient -Client $client | Out-Null
    $client = $null
} finally {
    if ($client) { Stop-RealClientProcess -Client $client }
}

Write-Host 'Played world checks:' -ForegroundColor Green
$results | ForEach-Object { Write-Host "  $_" -ForegroundColor Green }
