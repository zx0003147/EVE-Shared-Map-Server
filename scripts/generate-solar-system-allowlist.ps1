param(
    [Parameter(Mandatory = $true)]
    [string]$InputJsonl,

    [Parameter(Mandatory = $true)]
    [string]$SdeBuild,

    [string]$Output = (Join-Path $PSScriptRoot '..\src\main\resources\universe\solar-system-allowlist-v1.txt')
)

$ErrorActionPreference = 'Stop'
$resolvedInput = (Resolve-Path -LiteralPath $InputJsonl).Path
$ids = [System.Collections.Generic.HashSet[int]]::new()

$lineNumber = 0
Get-Content -LiteralPath $resolvedInput | ForEach-Object {
    $lineNumber += 1
    try {
        $record = $_ | ConvertFrom-Json
        $systemId = [int]$record._key
    } catch {
        throw "Invalid mapSolarSystems JSONL record at line $lineNumber."
    }
    if ($systemId -le 0 -or -not $ids.Add($systemId)) {
        throw "Invalid or duplicate solar-system ID at line $lineNumber."
    }
}

if ($ids.Count -eq 0) {
    throw 'The source contains no solar systems.'
}

$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedInput).Hash.ToLowerInvariant()
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('format=1')
$lines.Add("universeBuild=sde-$SdeBuild")
$lines.Add('source=CCP EVE Online SDE JSONL mapSolarSystems.jsonl')
$lines.Add("sourceSha256=$sourceHash")
$lines.Add("systemCount=$($ids.Count)")
$lines.Add('')
($ids | Sort-Object) | ForEach-Object { $lines.Add($_.ToString()) }

$resolvedOutput = [IO.Path]::GetFullPath($Output)
$outputDirectory = Split-Path -Parent $resolvedOutput
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
[IO.File]::WriteAllLines($resolvedOutput, $lines, [Text.UTF8Encoding]::new($false))
Write-Output "Wrote $($ids.Count) solar-system IDs to $resolvedOutput"
