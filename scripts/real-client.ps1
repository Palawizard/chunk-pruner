# Real game client helpers, dot-sourced after common.ps1 by the checks that need the real
# Minecraft client (scripts/check-played-world.ps1). The client is started by the portablemc
# launcher with an offline account and opens a game window, so these checks run on a desktop,
# never in CI. They follow the real client tooling of Player Data Rescue (scripts/real-client.ps1
# on its dev branch, ADR-PDR-011 there). Written for Windows PowerShell 5.1 and PowerShell 7.

$PortableMcVersion = '5.0.6'

function Get-PortableMc {
    # The launcher is installed by hand, outside the repository (docs/TESTING.md, Played world with
    # the real client): its path comes from -Launcher or the CHUNKPRUNER_PORTABLEMC environment variable.
    param([string]$Launcher)
    $path = $Launcher
    if (-not $path) { $path = $env:CHUNKPRUNER_PORTABLEMC }
    if (-not $path) { throw "No portablemc launcher: pass -Launcher or set CHUNKPRUNER_PORTABLEMC (portablemc $PortableMcVersion, see docs/TESTING.md)." }
    if (-not (Test-Path -LiteralPath $path)) { throw "The portablemc launcher $path does not exist." }
    $version = (& $path '--version' | Select-Object -First 1)
    if ("$version".Trim() -ne "portablemc $PortableMcVersion") {
        throw "portablemc $PortableMcVersion is required, $path is '$version'."
    }
    return (Resolve-Path -LiteralPath $path).Path
}

function Get-ClientRoot {
    # Versions, libraries and assets shared by every client run, from -ClientRoot or
    # CHUNKPRUNER_CLIENT_ROOT, else build/real-client/.minecraft.
    param([string]$ClientRoot)
    $root = $ClientRoot
    if (-not $root) { $root = $env:CHUNKPRUNER_CLIENT_ROOT }
    if (-not $root) { $root = Join-Path $ProjectRoot 'build/real-client/.minecraft' }
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    return (Resolve-Path -LiteralPath $root).Path
}

function Write-ClientOptions {
    # Skips the first-launch screens and keeps the client light: no sound and no pause when the
    # window loses focus. The render distance sets how far the server sends chunks to this player.
    param([string]$GameDir, [int]$RenderDistance = 6)
    New-Item -ItemType Directory -Path $GameDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $GameDir 'options.txt') -Encoding ascii -Value @(
        'onboardAccessibility:false'
        'skipMultiplayerWarning:true'
        'joinedFirstServer:true'
        'tutorialStep:none'
        'pauseOnLostFocus:false'
        'soundCategory_master:0.0'
        "renderDistance:$RenderDistance"
        'simulationDistance:5'
        'maxFps:30'
        'narrator:0'
    )
}

function Format-ProcessArgument {
    # Start-Process joins its arguments with spaces and quotes none of them, so a path such as
    # C:\Program Files\... would reach the launcher as two arguments.
    param([string]$Value)
    if ($Value -match '[\s"]') { return '"' + ($Value -replace '"', '\"') + '"' }
    return $Value
}

function Start-RealClient {
    # Starts the client as player $Name, joining 127.0.0.1:$Port or opening the singleplayer
    # world $World of $GameDir. Returns once the launcher has started the game process.
    param([string]$Launcher, [string]$Root, [string]$Version, [string]$JavaExe, [string]$Name, [string]$GameDir,
        [int]$Port, [string]$World)
    Write-ClientOptions -GameDir $GameDir
    foreach ($stale in 'logs', 'crash-reports') {
        $path = Join-Path $GameDir $stale
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    }
    $output = Join-Path $GameDir 'launcher.out.log'
    $launchArgs = @('--main-dir', $Root, '--output', 'machine', 'start', '--mc-dir', $GameDir, '--jvm', $JavaExe,
        '--jvm-arg=-Xmx2G', '-u', $Name)
    if ($World) { $launchArgs += @('--join-world', $World) }
    else { $launchArgs += @('--join-server', '127.0.0.1', '--join-server-port', "$Port") }
    $launchArgs += $Version
    $startArgs = @{
        FilePath               = $Launcher
        ArgumentList           = @($launchArgs | ForEach-Object { Format-ProcessArgument $_ })
        WorkingDirectory       = $GameDir
        RedirectStandardOutput = $output
        RedirectStandardError  = (Join-Path $GameDir 'launcher.err.log')
        PassThru               = $true
    }
    if ($OnWindows) { $startArgs.WindowStyle = 'Hidden' }
    $launcherProcess = Start-Process @startArgs
    $client = $null
    try {
        # The first run of a version downloads it, so the launcher can take minutes before the game starts.
        $deadline = (Get-Date).AddMinutes(10)
        while ((Get-Date) -lt $deadline) {
            $text = Get-Content -LiteralPath $output -Raw -ErrorAction SilentlyContinue
            if ($text -match "(?m)^launched\t(\d+)") {
                $client = @{ Launcher = $launcherProcess; GamePid = [int]$Matches[1]; GameDir = $GameDir; Name = $Name }
                return $client
            }
            if ($launcherProcess.HasExited) {
                throw "portablemc stopped before starting the $Version client (exit code $($launcherProcess.ExitCode)), see $output."
            }
            Start-Sleep -Milliseconds 500
        }
        throw "portablemc did not start the $Version client within 10 minutes, see $output."
    } finally {
        # A failure or Ctrl+C while waiting must not leave the launcher to open a game later.
        if (-not $client) { Stop-RealClientProcess -Client @{ Launcher = $launcherProcess; GamePid = 0 } }
    }
}

function Get-RealClientLog {
    param([hashtable]$Client)
    $latest = Join-Path $Client.GameDir 'logs/latest.log'
    if (-not (Test-Path -LiteralPath $latest)) { return '' }
    return [string](Get-Content -LiteralPath $latest -Raw -ErrorAction SilentlyContinue)
}

function Test-RealClientRunning {
    param([hashtable]$Client)
    return [bool](Get-Process -Id $Client.GamePid -ErrorAction SilentlyContinue)
}

function Wait-RealClientLog {
    param([hashtable]$Client, [string]$Pattern, [int]$Seconds = 60)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-RealClientLog -Client $Client) -match $Pattern) { return $true }
        if (-not (Test-RealClientRunning -Client $Client)) { return ((Get-RealClientLog -Client $Client) -match $Pattern) }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Stop-RealClientProcess {
    param([hashtable]$Client)
    foreach ($id in @($Client.GamePid, $Client.Launcher.Id)) {
        if ($id -gt 0) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    }
}

function Close-RealClient {
    # Closes the game window the way a player does, so the game stops cleanly and a singleplayer
    # world is saved. Windows: the window's close message. Hyprland: its window close dispatcher.
    # Elsewhere the game is stopped with a signal, not tried with a singleplayer world.
    # Returns true when the game stopped on its own within a minute; the process is killed otherwise.
    param([hashtable]$Client)
    $game = Get-Process -Id $Client.GamePid -ErrorAction SilentlyContinue
    if (-not $game) { return $true }
    if ($OnWindows) {
        $game.CloseMainWindow() | Out-Null
    } elseif ($env:HYPRLAND_INSTANCE_SIGNATURE) {
        # Hyprland 0.57.2 takes a Lua dispatcher; older releases take the plain one.
        $answer = & hyprctl dispatch "hl.dsp.window.close({ window = `"pid:$($Client.GamePid)`" })" 2>&1
        if ("$answer".Trim() -ne 'ok') { & hyprctl dispatch closewindow "pid:$($Client.GamePid)" | Out-Null }
    } else {
        & kill -TERM $Client.GamePid
    }
    $closed = $game.WaitForExit(60000)
    if (-not $closed) {
        Write-Host "  the $($Client.Name) client did not close within a minute, killing it"
        Stop-RealClientProcess -Client $Client
    }
    if (-not $Client.Launcher.WaitForExit(30000)) { Stop-RealClientProcess -Client $Client }
    return $closed
}

function Test-RealClientHealthy {
    # Problems a client shows when it does not survive a join: a crash report, a disconnect, or a
    # packet it could not handle. Returns the problems found.
    param([hashtable]$Client)
    $problems = @()
    $log = Get-RealClientLog -Client $Client
    $crashes = @(Get-ChildItem -LiteralPath (Join-Path $Client.GameDir 'crash-reports') -File -ErrorAction SilentlyContinue)
    if ($crashes.Count -gt 0) { $problems += "$($Client.Name): crash report $($crashes[0].Name)" }
    foreach ($pattern in 'Client disconnected with reason', 'Lost connection', 'Failed to handle packet', 'Exception caught in connection',
        'Error executing task', 'Disconnecting from server') {
        $line = @($log -split "`r?`n" | Where-Object { $_.Contains($pattern) }) | Select-Object -First 1
        if ($line) { $problems += "$($Client.Name): $($line.Trim())" }
    }
    if (-not (Test-RealClientRunning -Client $Client)) { $problems += "$($Client.Name): the game process stopped" }
    return , $problems
}

