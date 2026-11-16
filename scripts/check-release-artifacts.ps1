[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [string[]]$OnlyBands,
    # Check already built JARs found (recursively) under this folder instead of each
    # band's build/libs. CI uses it on the downloaded build artifacts.
    [string]$ArtifactsRoot
)

# Checks that every band and loader produced exactly one release JAR with its loader
# metadata, the core classes and the loader entrypoint and no test file, that no metadata
# placeholder was left unexpanded, that `java -jar <jar> --help` prints the command-line help,
# and that `java -jar <jar>` without --dry-run refuses to prune.

. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

$modProps = Get-ModProperties
$modId = $modProps['mod_id']
$modVersion = $modProps['mod_version']
$packagePath = ($modProps['mod_group'] -replace '\.', '/') + "/$modId"

if (-not $SkipBuild -and -not $ArtifactsRoot) {
    & (Join-Path $PSScriptRoot 'build-all-version-bands.ps1') -SkipSmoke -OnlyBands $OnlyBands
}

function Get-ReleaseJar {
    param([object]$LoaderConfig)
    $name = "$($LoaderConfig.artifact)-$modVersion.jar"
    $root = if ($ArtifactsRoot) { $ArtifactsRoot } else { Join-Path (Get-BandDirectory $LoaderConfig) 'build/libs' }
    $jars = @(Get-ChildItem -LiteralPath $root -Recurse -Filter $name -File -ErrorAction SilentlyContinue)
    if ($jars.Count -ne 1) { throw "Expected one $name under $root, found $($jars.Count)." }
    return $jars[0]
}

function Get-MetadataPath {
    param([string]$Loader)
    switch ($Loader) {
        'fabric' { 'fabric.mod.json' }
        'neoforge' { 'META-INF/neoforge.mods.toml' }
        default { 'META-INF/mods.toml' }
    }
}

function Get-EntrypointClass {
    param([string]$Loader)
    $suffix = switch ($Loader) { 'fabric' { 'Fabric' } 'neoforge' { 'NeoForge' } default { 'Forge' } }
    $className = (($modProps['mod_name'] -replace '[^A-Za-z]', '')) + $suffix
    "$packagePath/$Loader/$className.class"
}

$testSources = Join-Path $ProjectRoot 'core/src/test/java'
$testClassPrefixes = @(Get-ChildItem -LiteralPath $testSources -Recurse -Filter '*.java' -File | ForEach-Object {
        $relative = $_.FullName.Substring($testSources.Length + 1) -replace '\\', '/'
        $relative.Substring(0, $relative.Length - 5)
    })
if ($testClassPrefixes.Count -eq 0) { throw "No test sources found in $testSources." }

$failures = 0
foreach ($band in (Get-VersionMatrix).bands) {
    if ($OnlyBands -and ($band.id -notin $OnlyBands)) { continue }
    foreach ($loaderName in (Get-LoaderNames $band)) {
        $label = "$($band.id)/$loaderName"
        try {
            $jar = Get-ReleaseJar $band.loaders.$loaderName
            $metadataPath = Get-MetadataPath $loaderName
            $required = @($metadataPath, "$packagePath/core/ModInfo.class", (Get-EntrypointClass $loaderName))
            $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
            try {
                $entries = @($zip.Entries | ForEach-Object { $_.FullName })
                foreach ($entry in $required) {
                    if ($entries -notcontains $entry) { throw "$($jar.Name) is missing $entry." }
                }
                # Test code never ships: any class of core/src/test (the fixture tools, one of which
                # rewrites worlds), the join client and anything from testmod/.
                $testFiles = @($entries | Where-Object {
                        $entry = $_
                        $entry -like "$packagePath/core/fixture/*" -or $entry -like '*JoinBot*' -or $entry -like 'testmod/*' -or
                        @($testClassPrefixes | Where-Object { $entry -eq "$_.class" -or $entry.StartsWith("$_`$") }).Count -gt 0
                    })
                if ($testFiles.Count -gt 0) { throw "$($jar.Name) contains test files: $($testFiles -join ', ')." }
                $reader = New-Object System.IO.StreamReader($zip.GetEntry($metadataPath).Open())
                try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
                $reader = New-Object System.IO.StreamReader($zip.GetEntry('META-INF/MANIFEST.MF').Open())
                try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
            } finally {
                $zip.Dispose()
            }
            if ($metadata -match '\$\{') { throw "$($jar.Name): $metadataPath still has unexpanded placeholders." }
            if (-not $metadata.Contains($modVersion)) { throw "$($jar.Name): $metadataPath does not declare version $modVersion." }
            if ($manifest -notmatch "(?m)^Main-Class: $([regex]::Escape($modProps['mod_main_class']))\s*$") {
                throw "$($jar.Name): the manifest has no Main-Class: $($modProps['mod_main_class'])."
            }
            if ($manifest -notmatch "(?m)^Loader-Name: $loaderName\s*$") { throw "$($jar.Name): the manifest has no Loader-Name: $loaderName." }
            $java = Get-JavaExe -Version $band.java
            $help = Invoke-NativeCapture -FilePath $java -Arguments @('-jar', $jar.FullName, '--help')
            if ($help.Code -ne 0 -or $help.Output -notmatch 'Usage: java -jar') {
                throw "$($jar.Name): java -jar --help exited with $($help.Code) and printed no usage."
            }
            # This version only runs dry runs: without --dry-run the tool must refuse (exit code 3)
            # before it looks at the world.
            $refusal = Invoke-NativeCapture -FilePath $java -Arguments @('-jar', $jar.FullName, '--world', (Join-Path $ProjectRoot 'build/no-such-world'))
            if ($refusal.Code -ne 3 -or $refusal.Output -notmatch 'Pruning is not available') {
                throw "$($jar.Name): java -jar without --dry-run exited with $($refusal.Code) instead of refusing to prune."
            }
            Write-ResultLine -Status 'PASS' -Text "$label - $($jar.Name) ($($jar.Length) bytes)"
        } catch {
            $failures++
            Write-ResultLine -Status 'FAIL' -Text "$label - $($_.Exception.Message)"
        }
    }
}

if ($failures -gt 0) { throw "$failures release artifact check(s) failed." }
Write-Host 'All release artifacts verified.' -ForegroundColor Green
# The last java -jar run refused to prune on purpose (exit code 3); the script itself succeeded.
exit 0
