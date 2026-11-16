[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('1.20.1', '1.21.1', '26.3')]
    [string]$Minecraft,
    # The world folder to check (the one with level.dat). It is copied first, never changed.
    [Parameter(Mandatory)]
    [string]$World,
    [string]$Label,
    [switch]$SkipBuild,
    [int]$ServerPort = 25640,
    [int]$RconPort = 25650
)

# S2 on a real server: prunes a copy of the world with the test-only fixture tool (the command
# line refuses to prune in this version), opens the copy on the vanilla dedicated server of the
# same version, teleports the join client into a pruned area, and checks that the server loads
# the world without region errors and generates that chunk again.

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'vanilla-server.ps1')

$settings = Get-FixtureVersion $Minecraft
$javaExe = Get-JavaExe -Version $settings.Java
if (-not $Label) { $Label = Split-Path -Leaf (Split-Path -Parent ([IO.Path]::GetFullPath($World))) }
$checkRoot = Join-Path $ProjectRoot 'build/pruned-check'
$serverDir = Join-Path $checkRoot $Label
$coreDir = Join-Path $ProjectRoot 'core'
$classpathFile = Join-Path $coreDir 'build/fixture-classpath.txt'

if (-not $SkipBuild) {
    Invoke-Gradle -ProjectDirectory $coreDir -GradleJava (Get-VersionMatrix).core.gradleJava -Arguments @('writeFixtureClasspath')
}
if (-not (Test-Path -LiteralPath $classpathFile)) { throw "No $classpathFile. Run without -SkipBuild first." }
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()

function Invoke-FixtureTool {
    param([string[]]$Arguments)
    $output = & $javaExe -cp $classpath 'fr.palawi.chunkpruner.core.fixture.FixturePruneTool' @Arguments 2>&1 | ForEach-Object { "$_" }
    return @{ Code = $LASTEXITCODE; Output = ($output -join "`n") }
}

if (Test-Path -LiteralPath $serverDir) { Remove-Item -LiteralPath $serverDir -Recurse -Force }
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
$worldCopy = Join-Path $serverDir 'world'
Copy-Item -LiteralPath $World -Destination $worldCopy -Recurse
Remove-Item -LiteralPath (Join-Path $worldCopy 'chunkpruner') -Recurse -Force -ErrorAction SilentlyContinue

$pruned = Invoke-FixtureTool @('prune', $worldCopy)
Write-Host "  $($pruned.Output -replace "`n", "`n  ")"
if ($pruned.Code -ne 0 -or $pruned.Output -notmatch 'target (-?\d+) (-?\d+)') { throw "$Label`: the prune step failed." }
$targetX = [int]$Matches[1]
$targetZ = [int]$Matches[2]
if ((Invoke-FixtureTool @('absent', $worldCopy, "$targetX", "$targetZ")).Code -ne 0) { throw "$Label`: chunk $targetX, $targetZ is still there." }

$serverJar = Install-VanillaServer -Minecraft $Minecraft -CacheRoot (Join-Path $ProjectRoot 'build/fixtures/servers')
$rconPassword = [Guid]::NewGuid().ToString('N')
Write-VanillaServerConfig -ServerDir $serverDir -Properties @(
    'online-mode=false'
    'white-list=false'
    "server-port=$ServerPort"
    'enable-rcon=true'
    "rcon.port=$RconPort"
    "rcon.password=$rconPassword"
    'level-name=world'
    'view-distance=5'
    'simulation-distance=2'
    'gamemode=creative'
    'difficulty=peaceful'
)

$script:rconLog = @()
$server = Start-VanillaServer -ServerDir $serverDir -JavaExe $javaExe -ServerJar $serverJar
$bot = $null
try {
    Wait-ServerLog -ServerDir $serverDir -Process $server -Pattern 'Done \([^)]+\)! For help' -Seconds 300
    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command 'list' -Retries 30 | Out-Null
    Install-JoinBotClasses -JavaExe $javaExe -Destination (Join-Path $serverDir 'join-bot')
    $offset = (Get-ServerLogText $serverDir).Length
    $bot = Start-JoinBotProcess -JavaExe $javaExe -ClassDir 'join-bot' -WorkDir $serverDir -Port $ServerPort `
        -Protocol $settings.Protocol -Name 'Explorer' -StaySeconds 600 -ViewDistance 5
    Wait-ServerLog -ServerDir $serverDir -Process $server -Pattern 'Explorer\[[^\]]*\] logged in' -Seconds 120 -Offset $offset
    Start-Sleep -Seconds 3
    $blockX = $targetX * 16 + 8
    $blockZ = $targetZ * 16 + 8
    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command "tp Explorer $blockX 120 $blockZ" | Out-Null
    Start-Sleep -Seconds 15
    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command 'kick Explorer Check done' | Out-Null
    Wait-ServerLog -ServerDir $serverDir -Process $server -Pattern 'Explorer left the game' -Seconds 60 -Offset $offset
    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command 'save-all flush' | Out-Null
    Invoke-Rcon -Port $RconPort -Password $rconPassword -Command 'stop' | Out-Null
    if (-not $server.WaitForExit(120000)) { throw 'The server did not stop within 2 minutes.' }
} finally {
    if ($bot) { Stop-ProcessTree $bot }
    Stop-ProcessTree $server
}

$log = Get-ServerLogText $serverDir
if ($log -notmatch 'Stopping (the )?server') { throw "$Label`: the server did not log a clean shutdown." }
$problems = @($log -split "`r?`n" | Where-Object {
        $_ -match '/ERROR\]' -or $_ -match "Couldn't load chunk|invalid sector|is truncated|stream is missing|invalid chunk stream|wrong location"
    })
if ($problems.Count -gt 0) { throw "$Label`: the server logged region problems:`n$($problems -join "`n")" }
$generated = Invoke-FixtureTool @('generated', $worldCopy, "$targetX", "$targetZ")
if ($generated.Code -ne 0) { throw "$Label`: chunk $targetX, $targetZ was not generated again ($($generated.Output))." }
Write-Host "$Label OK: the pruned copy opened on the vanilla $Minecraft server, and chunk $targetX, $targetZ generated again ($($generated.Output))." -ForegroundColor Green
