[CmdletBinding()]
param(
    [ValidateSet('1.20.1', '1.21.1', '26.3')]
    [string]$Minecraft = '1.21.1',
    # Side of the square of full regions to generate, centred on region (0, 0).
    [int]$Side = 20,
    [string]$OutputRoot,
    [long]$Seed = 20261126,
    [int]$ServerPort = 25603,
    [int]$RconPort = 25653,
    [string]$Heap = '4G',
    # Regions force-loaded at the same time.
    [int]$Batch = 4
)

# Makes a large normal-terrain world for the scan benchmark (docs/ARCHITECTURE.md, Performance and
# measurements) with the vanilla dedicated server and no player. $Batch regions at a time are
# force-loaded (four /forceload areas of 16 by 16 chunks each); the script waits until every chunk
# of them answers `execute if loaded` (generated up to full status), then releases them so the
# server unloads and saves them. Each of the $Side by $Side regions around region (0, 0) ends with
# its 1024 chunks, and the generation around them leaves partly generated regions at the edge. The
# world is <OutputRoot>/server/world and is never committed; a run started again keeps it and
# skips the regions that are already full. Works on Windows PowerShell 5.1 and on PowerShell 7.

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'vanilla-server.ps1')

$settings = Get-FixtureVersion $Minecraft
$javaExe = Get-JavaExe -Version $settings.Java
if (-not $OutputRoot) { $OutputRoot = Join-Path $ProjectRoot "build/benchmark/world-$Minecraft-$Side" }
$serverDir = Join-Path $OutputRoot 'server'
$serverJar = Install-VanillaServer -Minecraft $Minecraft -CacheRoot (Join-Path $ProjectRoot 'build/fixtures/servers')
# A world left by an earlier, interrupted run is kept: its full regions are skipped below.
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
$password = [Guid]::NewGuid().ToString('N')
Write-VanillaServerConfig -ServerDir $serverDir -Properties @(
    'online-mode=false', 'white-list=false', "server-port=$ServerPort", 'enable-rcon=true', "rcon.port=$RconPort",
    "rcon.password=$password", "level-seed=$Seed", 'level-name=world', 'max-tick-time=-1', 'view-distance=4',
    'simulation-distance=4', 'spawn-protection=0')
# The log of an earlier run would show its 'Done' line before this server starts.
Remove-Item -LiteralPath (Join-Path $serverDir 'logs/latest.log') -ErrorAction SilentlyContinue
$server = Start-Process -FilePath $javaExe -ArgumentList @("-Xmx$Heap", '-jar', $serverJar, 'nogui') -WorkingDirectory $serverDir `
    -RedirectStandardOutput (Join-Path $serverDir 'server.stdout.log') -RedirectStandardError (Join-Path $serverDir 'server.stderr.log') -PassThru

function Send-Quiet([string]$Command) {
    # Invoke-Rcon prints every command; the region loop sends thousands.
    # A /forceload add can hold the server thread for several seconds; a lost answer is sent again.
    $answer = Invoke-Rcon -Port $RconPort -Password $password -Command $Command -Retries 5 6>$null
    $script:rconLog = @()
    return $answer
}

function Test-RegionFull([int]$Rx, [int]$Rz) {
    # The region file on disk already holds its 1024 chunks (an earlier run generated it).
    $file = Join-Path $serverDir "world/region/r.$Rx.$Rz.mca"
    if (-not (Test-Path -LiteralPath $file)) { return $false }
    $stream = [System.IO.File]::OpenRead($file)
    try {
        $header = New-Object byte[] 4096
        if ($stream.Read($header, 0, 4096) -ne 4096) { return $false }
    } finally { $stream.Dispose() }
    for ($i = 0; $i -lt 1024; $i++) {
        if ($header[4 * $i] -eq 0 -and $header[4 * $i + 1] -eq 0 -and $header[4 * $i + 2] -eq 0 -and $header[4 * $i + 3] -eq 0) { return $false }
    }
    return $true
}

function Test-RegionLoaded([int]$Rx, [int]$Rz) {
    for ($row = 0; $row -lt 32; $row++) {
        $z = ($Rz * 32 + $row) * 16 + 8
        $conditions = for ($col = 0; $col -lt 32; $col++) { "if loaded $(($Rx * 32 + $col) * 16 + 8) 0 $z" }
        if ((Send-Quiet "execute $($conditions -join ' ')") -notmatch 'Test passed') { return $false }
    }
    return $true
}

$started = Get-Date
$half = [int][Math]::Floor($Side / 2)
try {
    Wait-ServerLog -ServerDir $serverDir -Process $server -Pattern 'Done \([^)]+\)! For help' -Seconds 600
    Send-Quiet 'list' | Out-Null
    $done = 0
    $all = @(for ($rz = -$half; $rz -lt $Side - $half; $rz++) {
        for ($rx = -$half; $rx -lt $Side - $half; $rx++) { if (-not (Test-RegionFull $rx $rz)) { , @($rx, $rz) } }
    })
    Write-Host "$($all.Count) of $($Side * $Side) regions to generate"
    for ($i = 0; $i -lt $all.Count; $i += $Batch) {
        $group = $all[$i..([Math]::Min($i + $Batch, $all.Count) - 1)]
        foreach ($r in $group) {
            foreach ($q in @(@(0, 0), @(256, 0), @(0, 256), @(256, 256))) {
                $x = $r[0] * 512 + $q[0]
                $z = $r[1] * 512 + $q[1]
                $a = Send-Quiet "forceload add $x $z $($x + 255) $($z + 255)"
                if ($a -notmatch 'Marked') { throw "forceload add answered '$a'." }
            }
        }
        $deadline = (Get-Date).AddMinutes(20)
        foreach ($r in $group) {
            while (-not (Test-RegionLoaded $r[0] $r[1])) {
                if ((Get-Date) -gt $deadline) { throw "Region $($r[0]), $($r[1]) was not generated within 20 minutes." }
                Start-Sleep -Milliseconds 500
            }
        }
        Send-Quiet 'forceload remove all' | Out-Null
        $done += $group.Count
        $elapsed = ((Get-Date) - $started).TotalSeconds
        Write-Host ("{0}/{1} regions done ({2:N0} s)" -f $done, $all.Count, $elapsed)
    }
    Send-Quiet 'save-all flush' | Out-Null
    Send-Quiet 'stop' | Out-Null
    if (-not $server.WaitForExit(600000)) { throw 'The server did not stop within 10 minutes.' }
} finally {
    Stop-ProcessTree $server
}
$minutes = ((Get-Date) - $started).TotalMinutes
Write-Host ("Generated {0} regions in {1:N1} min. World: {2}" -f ($Side * $Side), $minutes, (Join-Path $serverDir 'world')) -ForegroundColor Green
