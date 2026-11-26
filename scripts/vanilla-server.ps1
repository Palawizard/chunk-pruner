# Helpers for the fixture world scripts, dot-sourced after common.ps1: a vanilla dedicated
# server downloaded from Mojang (checked against the SHA-1 of the version manifest), a small
# RCON client and the headless join client from testmod/join-bot.
# Works on Windows PowerShell 5.1 and on PowerShell 7.

$fixtureVersions = @{
    '1.20.1' = @{ Java = 17; Protocol = 763 }
    '1.21.1' = @{ Java = 21; Protocol = 767 }
    '26.3' = @{ Java = 25; Protocol = 777 }
}

function Get-FixtureVersion {
    param([string]$Minecraft)
    if (-not $fixtureVersions.ContainsKey($Minecraft)) {
        throw "No fixture settings for Minecraft $Minecraft. Known versions: $($fixtureVersions.Keys -join ', ')."
    }
    return $fixtureVersions[$Minecraft]
}

function Get-FileSha1 {
    param([string]$Path)
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        return ([BitConverter]::ToString($sha1.ComputeHash($stream)) -replace '-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $sha1.Dispose()
    }
}

function Install-VanillaServer {
    # Returns the path of the cached server JAR for one Minecraft version.
    param([string]$Minecraft, [string]$CacheRoot)
    $jar = Join-Path (Join-Path $CacheRoot $Minecraft) 'server.jar'
    $manifest = Invoke-RestMethod -Uri 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json' -TimeoutSec 120
    $entry = $manifest.versions | Where-Object { $_.id -eq $Minecraft } | Select-Object -First 1
    if (-not $entry) { throw "Minecraft $Minecraft is not in the Mojang version manifest." }
    $server = (Invoke-RestMethod -Uri $entry.url -TimeoutSec 120).downloads.server
    if ((Test-Path -LiteralPath $jar) -and ((Get-FileSha1 $jar) -eq $server.sha1)) { return $jar }
    New-Item -ItemType Directory -Path (Split-Path -Parent $jar) -Force | Out-Null
    Write-Host "  download $($server.url)"
    Invoke-WebRequest -Uri $server.url -OutFile "$jar.part" -UseBasicParsing -TimeoutSec 600
    $actual = Get-FileSha1 "$jar.part"
    if ($actual -ne $server.sha1) { throw "server.jar for $Minecraft has SHA-1 $actual, the manifest says $($server.sha1)." }
    Move-Item -LiteralPath "$jar.part" -Destination $jar -Force
    return $jar
}

function Write-VanillaServerConfig {
    param([string]$ServerDir, [string[]]$Properties)
    Set-Content -LiteralPath (Join-Path $ServerDir 'eula.txt') -Value 'eula=true' -Encoding ascii
    Set-Content -LiteralPath (Join-Path $ServerDir 'server.properties') -Value $Properties -Encoding ascii
}

function Start-VanillaServer {
    param([string]$ServerDir, [string]$JavaExe, [string]$ServerJar)
    $startArgs = @{
        FilePath               = $JavaExe
        ArgumentList           = @('-Xmx2G', '-jar', $ServerJar, 'nogui')
        WorkingDirectory       = $ServerDir
        RedirectStandardOutput = (Join-Path $ServerDir 'server.stdout.log')
        RedirectStandardError  = (Join-Path $ServerDir 'server.stderr.log')
        PassThru               = $true
    }
    if ($OnWindows) { $startArgs.WindowStyle = 'Hidden' }
    return Start-Process @startArgs
}

function Get-ServerLogText {
    param([string]$ServerDir)
    $latest = Join-Path $ServerDir 'logs/latest.log'
    if (-not (Test-Path -LiteralPath $latest)) { return '' }
    $text = Get-Content -LiteralPath $latest -Raw -ErrorAction SilentlyContinue
    if ($null -eq $text) { return '' }
    return $text
}

function Wait-ServerLog {
    param([string]$ServerDir, [System.Diagnostics.Process]$Process, [string]$Pattern, [int]$Seconds, [int]$Offset = 0)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $text = Get-ServerLogText $ServerDir
        if ($text.Length -gt $Offset -and $text.Substring($Offset) -match $Pattern) { return }
        if ($Process.HasExited) { throw "The server stopped while waiting for '$Pattern'." }
        Start-Sleep -Milliseconds 500
    }
    throw "The server log has no '$Pattern' after $Seconds s."
}

function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process)
    if ($Process.HasExited) { return }
    try { $Process.Kill() } catch { Write-Host "  could not kill process $($Process.Id)" }
    $Process.WaitForExit(30000) | Out-Null
}

function Read-RconBytes {
    param([System.IO.Stream]$Stream, [int]$Count)
    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) { throw 'RCON connection closed.' }
        $offset += $read
    }
    return , $buffer
}

function Send-RconRequest {
    param([System.IO.Stream]$Stream, [int]$RequestId, [int]$Type, [string]$Body)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $length = 4 + 4 + $bytes.Length + 2
    $packet = New-Object byte[] (4 + $length)
    [Array]::Copy([BitConverter]::GetBytes([int]$length), 0, $packet, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes($RequestId), 0, $packet, 4, 4)
    [Array]::Copy([BitConverter]::GetBytes($Type), 0, $packet, 8, 4)
    [Array]::Copy($bytes, 0, $packet, 12, $bytes.Length)
    $Stream.Write($packet, 0, $packet.Length)
    $Stream.Flush()
}

function Read-RconReply {
    param([System.IO.Stream]$Stream)
    $lengthBytes = Read-RconBytes -Stream $Stream -Count 4
    $payload = Read-RconBytes -Stream $Stream -Count ([BitConverter]::ToInt32($lengthBytes, 0))
    return @{
        Id   = [BitConverter]::ToInt32($payload, 0)
        Body = [System.Text.Encoding]::UTF8.GetString($payload, 8, $payload.Length - 10)
    }
}

function Invoke-Rcon {
    # Sends one command and returns the server's answer. Each command is also appended to
    # $script:rconLog so the fixture note can list them.
    param([int]$Port, [string]$Password, [string]$Command, [int]$Retries = 0)
    for ($attempt = 0; $attempt -le $Retries; $attempt++) {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $client.Connect('127.0.0.1', $Port)
            $stream = $client.GetStream()
            $stream.ReadTimeout = 15000
            Send-RconRequest -Stream $stream -RequestId 1 -Type 3 -Body $Password
            if ((Read-RconReply -Stream $stream).Id -ne 1) { throw 'RCON authentication failed.' }
            Send-RconRequest -Stream $stream -RequestId 2 -Type 2 -Body $Command
            $answer = (Read-RconReply -Stream $stream).Body.Trim()
            $script:rconLog += , @{ Time = (Get-Date).ToUniversalTime().ToString('HH:mm:ss'); Command = $Command; Answer = $answer }
            Write-Host "  rcon> $Command -> $answer"
            return $answer
        } catch {
            if ($attempt -ge $Retries) { throw }
            Start-Sleep -Seconds 1
        } finally {
            $client.Dispose()
        }
    }
}

function Install-JoinBotClasses {
    param([string]$JavaExe, [string]$Destination)
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
    $javacName = 'javac'
    if ($OnWindows) { $javacName = 'javac.exe' }
    $javac = Join-Path (Split-Path -Parent $JavaExe) $javacName
    & $javac -d $Destination (Join-Path $ProjectRoot 'testmod/join-bot/JoinBot.java')
    if ($LASTEXITCODE -ne 0) { throw "javac failed on the join bot (exit code $LASTEXITCODE)." }
}

function Start-JoinBotProcess {
    param([string]$JavaExe, [string]$ClassDir, [string]$WorkDir, [int]$Port, [int]$Protocol, [string]$Name,
        [int]$StaySeconds, [int]$ViewDistance)
    $botArgs = @('-cp', $ClassDir, 'JoinBot', '--port', "$Port", '--protocol', "$Protocol", '--name', $Name,
        '--stay-seconds', "$StaySeconds", '--timeout-seconds', '120', '--view-distance', "$ViewDistance")
    $startArgs = @{
        FilePath               = $JavaExe
        ArgumentList           = $botArgs
        WorkingDirectory       = $WorkDir
        RedirectStandardOutput = (Join-Path $WorkDir "bot-$Name.log")
        RedirectStandardError  = (Join-Path $WorkDir "bot-$Name.err.log")
        PassThru               = $true
    }
    if ($OnWindows) { $startArgs.WindowStyle = 'Hidden' }
    return Start-Process @startArgs
}

function Assert-JoinBotKicked {
    # After an RCON kick the client must log the server's disconnect with its reason, even when a
    # position report failed on the closed connection first.
    param([System.Diagnostics.Process]$Process, [string]$WorkDir, [string]$Name, [string]$Reason)
    if (-not $Process.WaitForExit(30000)) { throw "The join bot $Name did not exit within 30 s of its kick." }
    # The overload without a timeout also waits for the redirected output to be written.
    $Process.WaitForExit()
    $text = Get-JoinBotLog $WorkDir $Name
    if ($text -notmatch "disconnected by the server, joined true, reason: .*$([regex]::Escape($Reason))") {
        throw "The join bot $Name did not report its kick with the reason '$Reason': $text"
    }
}

function Get-JoinBotLog {
    param([string]$WorkDir, [string]$Name)
    $path = Join-Path $WorkDir "bot-$Name.log"
    if (-not (Test-Path -LiteralPath $path)) { return '' }
    $text = Get-Content -LiteralPath $path -Raw -ErrorAction SilentlyContinue
    if ($null -eq $text) { return '' }
    return $text
}
