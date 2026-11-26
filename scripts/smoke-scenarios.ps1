# Scenarios of the production-JAR smoke test, dot-sourced by prod-smoke.ps1 after common.ps1 and
# vanilla-server.ps1. They use its variables ($band, $BandId, $Loader, $serverDir, $ServerPort,
# $RconPort, $rconPassword) and run over four boots of the same world:
#   1. S9: the headless join client, a vanilla client without the mod, joins, travels and is kicked.
#      S10: with a dry run scheduled and no dry-run report yet, `schedule prune` is refused.
#   2. The scheduled dry run runs at startup, before the levels load; `schedule prune` is accepted.
#   3. The scheduled prune runs at startup, with a backup; the world then loads without region
#      errors. S12: on 1.21.1 and 26.3 the server writes LZ4 regions, and the prune reads them.
#   4. Auto-prune, turned on in the config, runs a dry run at startup and records it.
# Works on Windows PowerShell 5.1 and on PowerShell 7.

function Test-SmokeLz4 {
    # region-file-compression exists from 1.20.5 (S12).
    return $band.compileTarget -ne '1.20.1'
}

function Get-SmokeServerProperties {
    if (Test-SmokeLz4) { return @('region-file-compression=lz4') }
    return @()
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
        Start-Sleep -Seconds 10
        Invoke-SmokeRcon -Command 'fill 798 119 798 802 119 802 minecraft:glass' -Pattern 'Successfully filled' | Out-Null
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

    $log = Invoke-Server -JavaExe $JavaExe -LaunchArgs $LaunchArgs -Boot 'boot 2: scheduled dry run' -WhileRunning {
        Invoke-SmokeRcon -Command 'chunkpruner report' -Pattern 'Would remove [0-9,]+ unvisited chunk' | Out-Null
        Invoke-SmokeRcon -Command 'chunkpruner schedule prune' -Pattern 'A prune will run at the next server startup' | Out-Null
    }
    Assert-SmokeLog -Log $log -Pattern 'Chunk Pruner: a dry run for the pending schedule before the world loads' -What 'E1 dry run'
    Assert-SmokeLog -Log $log -Pattern 'Chunk Pruner: Would remove [0-9,]+ unvisited chunk' -What 'E1 dry run result'

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
}
