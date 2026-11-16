[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('1.20.1', '1.21.1', '26.3')]
    [string]$Minecraft,
    # Folder name under core/src/test/fixtures/worlds. Defaults to the Minecraft version.
    [string]$Name,
    # 'strip': an explored strip and a flown-over area. 'spawn': a short stay at spawn only.
    [ValidateSet('strip', 'spawn')]
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
            Invoke-Rcon -Port $RconPort -Password $rconPassword -Command $fill | Out-Null
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
