# Scenarios of the production-JAR smoke test, dot-sourced by prod-smoke.ps1 after common.ps1 and
# vanilla-server.ps1. They use its variables ($band, $BandId, $Loader, $modProps, $serverDir,
# $ServerPort, $RconPort, $rconPassword, $TimeoutMinutes, $OnWindows) and run over five boots of
# the same world:
#   1. S9: the headless join client, a vanilla client without the mod, joins, travels and is kicked.
#      S10: with a dry run scheduled and no dry-run report yet, `schedule prune` is refused.
#   2. The scheduled dry run runs at startup, before the levels load; `schedule prune` is accepted.
#   3. The scheduled prune runs at startup, with a backup; the world then loads without region
#      errors, and its report gives the mod version. S12: on 1.21.1 and 26.3 the server writes LZ4
#      regions, and the prune reads them.
#   4. Auto-prune, turned on in the config, runs a dry run at startup and records it.
#   5. A world whose unfinished run cannot be finished does not load, and no file of it changes.
# Boot 2 also checks that /chunkpruner needs permission level 4: functions load at level 3 there.
# Works on Windows PowerShell 5.1 and on PowerShell 7.

function Test-SmokeLz4 {
    # region-file-compression exists from 1.20.5 (S12).
    return $band.compileTarget -ne '1.20.1'
}

function Get-SmokeServerProperties {
    $properties = @('function-permission-level=3')
    if (Test-SmokeLz4) { $properties += 'region-file-compression=lz4' }
    return $properties
}

function Install-SmokePermissionPack {
    # Two functions the game parses at function-permission-level 3 when the world loads: one with a
    # level 2 command, one with /chunkpruner, which must not load.
    $pack = Join-Path (Get-SmokeWorld) 'datapacks/chunkpruner-smoke'
    $meta = switch ($band.compileTarget) {
        '1.20.1' { '{"pack": {"pack_format": 15, "description": "Chunk Pruner smoke"}}' }
        '1.21.1' { '{"pack": {"pack_format": 48, "description": "Chunk Pruner smoke"}}' }
        default { '{"pack": {"min_format": 121, "max_format": 121, "description": "Chunk Pruner smoke"}}' }
    }
    $folder = if ($band.compileTarget -eq '1.20.1') { 'functions' } else { 'function' }
    $functions = Join-Path $pack "data/chunkpruner_smoke/$folder"
    New-Item -ItemType Directory -Path $functions -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $pack 'pack.mcmeta') -Value $meta -Encoding ascii
    Set-Content -LiteralPath (Join-Path $functions 'allowed.mcfunction') -Value 'say chunkpruner-smoke-allowed' -Encoding ascii
    Set-Content -LiteralPath (Join-Path $functions 'owner.mcfunction') -Value 'chunkpruner status' -Encoding ascii
}

function Assert-SmokePermission {
    Invoke-SmokeRcon -Command 'function chunkpruner_smoke:allowed' | Out-Null
    $owner = Invoke-SmokeRcon -Command 'function chunkpruner_smoke:owner'
    $log = Get-ServerLog
    if ($log -notmatch 'chunkpruner-smoke-allowed') { throw 'The smoke data pack did not load: its level 2 function did not run.' }
    if ($owner -match 'Scheduled:') { throw "A function at permission level 3 ran /chunkpruner: '$owner'." }
    if ($log -notmatch 'Failed to load function chunkpruner_smoke:owner') { throw "The function with /chunkpruner loaded at permission level 3 (RCON answered '$owner')." }
    Write-Host '  /chunkpruner needs permission level 4: a level 3 function with it does not load'
}

function Get-SmokeChunkCompression {
    # The compression byte of the first chunk of r.0.0.mca in the overworld (4 is LZ4).
    $file = Get-ChildItem -LiteralPath (Get-SmokeWorld) -Recurse -Filter 'r.0.0.mca' -File |
        Where-Object { (Split-Path -Leaf (Split-Path -Parent $_.FullName)) -eq 'region' } |
        Where-Object { $_.FullName -notmatch '[\\/](DIM-?1|the_nether|the_end)[\\/]' } | Select-Object -First 1
    if (-not $file) { throw 'No overworld r.0.0.mca in the smoke world.' }
    $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
    for ($i = 0; $i -lt 1024; $i++) {
        $sector = ([int]$bytes[$i * 4] -shl 16) -bor ([int]$bytes[$i * 4 + 1] -shl 8) -bor [int]$bytes[$i * 4 + 2]
        if ($sector -gt 0) { return [int]$bytes[$sector * 4096 + 4] }
    }
    throw "$($file.FullName) has no chunk."
}

function Get-SmokeWorldHashes {
    $world = Get-SmokeWorld
    $hashes = @{}
    Get-ChildItem -LiteralPath $world -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($world.Length + 1).Replace('\', '/')
        if (-not $relative.StartsWith('chunkpruner/') -and $relative -ne 'session.lock') {
            $hashes[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
    }
    return $hashes
}

function Invoke-SmokeRefusedBoot {
    # A world whose journal cannot be finished must not load: the server stops before its levels.
    param([string]$JavaExe, [string[]]$LaunchArgs)
    $journal = Join-Path (Get-SmokeWorld) 'chunkpruner/journal.json'
    Set-Content -LiteralPath $journal -Value '{}' -Encoding ascii
    $before = Get-SmokeWorldHashes
    $stdout = Join-Path $serverDir 'smoke.stdout.log'
    $stderr = Join-Path $serverDir 'smoke.stderr.log'
    Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $serverDir 'logs/latest.log') -Force -ErrorAction SilentlyContinue
    Write-Host "Starting the $BandId/$Loader dedicated server with the release JAR (boot 5: unfinished run)"
    $startArgs = @{
        FilePath               = $JavaExe
        ArgumentList           = $LaunchArgs
        WorkingDirectory       = $serverDir
        RedirectStandardOutput = $stdout
        RedirectStandardError  = $stderr
        PassThru               = $true
    }
    if ($OnWindows) { $startArgs.WindowStyle = 'Hidden' }
    $process = Start-Process @startArgs
    try {
        if (-not $process.WaitForExit($TimeoutMinutes * 60000)) { throw "$BandId/$Loader kept running with an unfinished run." }
    } finally {
        if (-not $process.HasExited) {
            try { $process.Kill($true) } catch { & taskkill.exe /PID $process.Id /T /F 2>$null | Out-Null }
            $process.WaitForExit(30000) | Out-Null
        }
    }
    $log = Get-ServerLog
    if ($log -match 'Done \([^)]+\)! For help') { throw "$BandId/$Loader loaded a world with an unfinished run." }
    if ($log -notmatch 'Chunk Pruner: the run could not be finished, so the world is not started') { throw "$BandId/$Loader stopped without the Chunk Pruner reason in its log." }
    $after = Get-SmokeWorldHashes
    $changed = @($before.Keys + $after.Keys | Sort-Object -Unique | Where-Object { $before[$_] -ne $after[$_] })
    if ($changed.Count -gt 0) { throw "$BandId/$Loader changed world files while refusing to start: $($changed -join ', ')" }
    Remove-Item -LiteralPath $journal -Force
    Write-Host "  E1: a world with an unfinished run that cannot be finished did not load, and none of its $($before.Count) files changed"
}

function Invoke-SmokeRcon {
    param([string]$Command, [string]$Pattern)
    $answer = Invoke-Rcon -Port $RconPort -Password $rconPassword -Command $Command -Retries 30
    if ($Pattern -and $answer -notmatch $Pattern) { throw "RCON '$Command' answered '$answer', expected /$Pattern/." }
    return $answer
}

function Get-SmokeWorld {
    return Join-Path $serverDir 'smokeworld'
}

function Get-LatestSmokeReport {
    $reports = Join-Path (Get-SmokeWorld) 'chunkpruner/reports'
    $json = Get-ChildItem -LiteralPath $reports -Filter '*.json' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $json) { throw "No report in $reports." }
    return Get-Content -LiteralPath $json.FullName -Raw | ConvertFrom-Json
}

function Assert-SmokeLog {
    # The line must be in this boot's log, and before the server reported it was ready.
    param([string]$Log, [string]$Pattern, [string]$What)
    $match = [regex]::Match($Log, $Pattern)
    if (-not $match.Success) { throw "$What`: the log has no /$Pattern/." }
    $done = [regex]::Match($Log, 'Done \([^)]+\)! For help')
    if ($done.Success -and $match.Index -gt $done.Index) { throw "$What`: /$Pattern/ came after the server was ready." }
    Write-Host "  $What`: $($Log.Substring($match.Index, [Math]::Min(160, $Log.Length - $match.Index)).Split("`n")[0].Trim())"
}

function Invoke-SmokeJoin {
    # S9: a client without the mod joins, moves far away (the server generates a second area there)
    # and leaves when kicked.
    $settings = Get-FixtureVersion $band.compileTarget
    Install-JoinBotClasses -JavaExe $javaExe -Destination (Join-Path $serverDir 'join-bot')
    $offset = (Get-ServerLogText $serverDir).Length
    $bot = Start-JoinBotProcess -JavaExe $javaExe -ClassDir 'join-bot' -WorkDir $serverDir -Port $ServerPort `
        -Protocol $settings.Protocol -Name 'S9Bot' -StaySeconds 600 -ViewDistance 4
    try {
        Wait-ServerLog -ServerDir $serverDir -Process $script:serverProcess -Pattern 'S9Bot\[[^\]]*\] logged in' -Seconds 120 -Offset $offset
        Start-Sleep -Seconds 3
        Invoke-SmokeRcon -Command 'tp S9Bot 800 120 800' -Pattern 'Teleported' | Out-Null
        # The destination is new terrain: give the server up to 40 s to generate and load it.
        $deadline = (Get-Date).AddSeconds(40)
        do {
            Start-Sleep -Seconds 5
            $answer = Invoke-SmokeRcon -Command 'fill 798 119 798 802 119 802 minecraft:glass'
        } while ($answer -notmatch 'Successfully filled' -and (Get-Date) -lt $deadline)
        if ($answer -notmatch 'Successfully filled') { throw "The fill at the teleport destination answered '$answer' after 40 s." }
        Invoke-SmokeRcon -Command 'kick S9Bot Smoke done' -Pattern 'Kicked' | Out-Null
        Assert-JoinBotKicked -Process $bot -WorkDir $serverDir -Name 'S9Bot' -Reason 'Smoke done'
        Write-Host '  S9: a client without the mod joined, moved and left normally'
    } finally {
        Stop-ProcessTree $bot
    }
}

function Invoke-SmokeScenarios {
    param([string]$JavaExe, [string[]]$LaunchArgs)
    $javaExe = $JavaExe

    Invoke-Server -JavaExe $JavaExe -LaunchArgs $LaunchArgs -Boot 'boot 1: S9 and S10' -WhileRunning {
        Invoke-SmokeJoin
        Invoke-SmokeRcon -Command 'chunkpruner status' -Pattern 'Scheduled: nothing\.' | Out-Null
        Invoke-SmokeRcon -Command 'chunkpruner schedule dry-run' -Pattern 'A dry run will run at the next server startup' | Out-Null
        Invoke-SmokeRcon -Command 'chunkpruner schedule prune' -Pattern 'Refused: a prune needs a dry run of this world' | Out-Null
        Invoke-SmokeRcon -Command 'chunkpruner status' -Pattern 'Scheduled: a dry-run at the next server startup' | Out-Null
        Write-Host '  S10: schedule prune refused without a recent dry run, the dry run stays scheduled'
    } | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $serverDir 'config/chunkpruner.json'))) { throw 'The mod did not write config/chunkpruner.json.' }
    Install-SmokePermissionPack

    $log = Invoke-Server -JavaExe $JavaExe -LaunchArgs $LaunchArgs -Boot 'boot 2: scheduled dry run' -WhileRunning {
        Invoke-SmokeRcon -Command 'chunkpruner report' -Pattern 'Would remove [0-9,]+ unvisited chunk' | Out-Null
        Invoke-SmokeRcon -Command 'chunkpruner schedule prune' -Pattern 'A prune will run at the next server startup' | Out-Null
        Assert-SmokePermission
    }
    Assert-SmokeLog -Log $log -Pattern 'Chunk Pruner: a dry run for the pending schedule before the world loads' -What 'E1 dry run'
    Assert-SmokeLog -Log $log -Pattern 'Chunk Pruner: Would remove [0-9,]+ unvisited chunk' -What 'E1 dry run result'
    if (Test-SmokeLz4) {
        $compression = Get-SmokeChunkCompression
        if ($compression -ne 4) { throw "S12: the server wrote its regions with compression $compression, not LZ4 (4)." }
    }

    $log = Invoke-Server -JavaExe $JavaExe -LaunchArgs $LaunchArgs -Boot 'boot 3: scheduled prune' -WhileRunning {
        Invoke-SmokeRcon -Command 'chunkpruner status' -Pattern 'Scheduled: nothing\.' | Out-Null
        Invoke-SmokeRcon -Command 'chunkpruner report' -Pattern 'Removed [0-9,]+ unvisited chunk' | Out-Null
    }
    Assert-SmokeLog -Log $log -Pattern 'Chunk Pruner: pruning for the pending schedule before the world loads' -What 'E1 prune'
    Assert-SmokeLog -Log $log -Pattern 'Chunk Pruner: Removed [0-9,]+ unvisited chunk' -What 'E1 prune result'
    $problems = @($log -split "`r?`n" | Where-Object { $_ -match "Couldn't load chunk|invalid sector|is truncated|stream is missing|invalid chunk stream|wrong location" })
    if ($problems.Count -gt 0) { throw "After the prune the server logged region problems:`n$($problems -join "`n")" }
    $report = Get-LatestSmokeReport
    if ($report.mode -ne 'prune' -or [long]$report.totals.chunksPruned -le 0) { throw "The prune report removed nothing: $($report.totals | ConvertTo-Json -Compress)" }
    if ($report.versions.mod -ne $modProps['mod_version']) { throw "The prune report gives mod version '$($report.versions.mod)', expected '$($modProps['mod_version'])'." }
    $restore = Get-ChildItem -LiteralPath (Join-Path (Get-SmokeWorld) 'chunkpruner/backups') -Recurse -Filter 'restore.txt' -File -ErrorAction SilentlyContinue
    if (-not $restore) { throw 'The prune left no backup with restore.txt.' }
    $unreadable = @($report.warnings | Where-Object { $_.type -eq 'unreadable-chunk' -or $_.type -eq 'unreadable-region' })
    if ($unreadable.Count -gt 0) { throw "The prune report has unreadable chunks: $($unreadable | ConvertTo-Json -Compress)" }
    if (Test-SmokeLz4) {
        if (-not $report.lz4Available) { throw 'S12: the engine had no LZ4 codec in game.' }
        Write-Host "  S12: LZ4 regions pruned in game, $($report.totals.chunksPruned) chunks removed, no chunk kept as unreadable"
    }

    $configFile = Join-Path $serverDir 'config/chunkpruner.json'
    $config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
    $config.autoPrune.enabled = $true
    Set-Content -LiteralPath $configFile -Value ($config | ConvertTo-Json -Depth 10) -Encoding ascii
    $log = Invoke-Server -JavaExe $JavaExe -LaunchArgs $LaunchArgs -Boot 'boot 4: auto-prune' -WhileRunning {
        Invoke-SmokeRcon -Command 'chunkpruner status' -Pattern 'Auto-prune: dry-run every 7 days, next at the first startup after' | Out-Null
    }
    Assert-SmokeLog -Log $log -Pattern 'Chunk Pruner: a dry run for auto-prune before the world loads' -What 'E1 auto-prune'
    if (-not (Test-Path -LiteralPath (Join-Path (Get-SmokeWorld) 'chunkpruner/auto-prune.json'))) { throw 'Auto-prune did not record its run.' }

    Invoke-SmokeRefusedBoot -JavaExe $JavaExe -LaunchArgs $LaunchArgs
}
