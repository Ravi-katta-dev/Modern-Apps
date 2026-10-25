# build_world_transit.ps1 -- build inputs/world.transit, the single global offline transit pack.
#
# Mirrors Transitous' own published GTFS directory (https://api.transitous.org/gtfs/, its
# already-fetched, already-normalised output; nothing here talks to an agency), unzips every
# feed, and merges them into one TRIX pack with `gtfs_ingest`.
#
# The pack is the build's only transit input: v7 carries RAIL_LINES, the exact shapes
# `transit_shapes` reads from the feeds, so build_graph.ps1 derives the map's transit layer from
# world.transit alone. Before the pack replaces inputs/world.transit this script proves that:
# the layer derived from the pack must be byte-identical to the one derived from the raw feeds.
# Once it has, the work dir (mirror + unzipped feeds, ~80 GB) is not needed again and can be
# deleted; a later run re-downloads it.
#
# THE FEED ID. A zip is `<region>_<Source>.gtfs.zip` and its MOTIS feed id is `<region>-<Source>`.
# Only the FIRST underscore is a separator; the rest keeps its own hyphens and case
# (`us-ca_SF-bayarea` -> `us-ca-SF-bayarea`). Without it the pack carries no MOTIS stop ids and
# live delays cannot be matched to a stop.
#
# Usage (from anywhere):
#   .\scripts\maps\build_world_transit.ps1               # mirror, unzip, merge, verify, install
#   .\scripts\maps\build_world_transit.ps1 -SkipMirror   # reuse the mirror already on disk
#   .\scripts\maps\build_world_transit.ps1 -SkipVerify   # skip the raw-vs-pack layer proof
#
# Work dir: scripts/maps/world_transit_work (transitous/ zips, gtfs/ unzipped, feeds.manifest).
# Output:   scripts/maps/inputs/world.transit (+ .json).

param(
    # Use the zips already in the work dir instead of refreshing the mirror.
    [switch] $SkipMirror,
    # Install the pack without proving the derived transit layer matches the raw feeds.
    [switch] $SkipVerify,
    # Feeds unzipped at once. The download itself is always serial; see below.
    [int] $Jobs = 4
)
$ErrorActionPreference = "Stop"

$Here = $PSScriptRoot
$Repo = (Resolve-Path (Join-Path $Here "../..")).Path
$Work = Join-Path $Here "world_transit_work"
$Mirror = Join-Path $Work "transitous"
$Gtfs = Join-Path $Work "gtfs"
$Manifest = Join-Path $Work "feeds.manifest"
$Staging = Join-Path $Work "pack"
$Inputs = Join-Path $Here "inputs"
$IndexUrl = "https://api.transitous.org/gtfs/"
New-Item -ItemType Directory -Force -Path $Work, $Mirror, $Gtfs, $Inputs | Out-Null

# --- 1. Build the tools --------------------------------------------------------------------
Write-Host "=== [1/5] building gtfs_ingest + transit_shapes (release) ==="
Push-Location $Repo
try {
    cargo build --release -p gtfs_ingest --bin gtfs_ingest --bin transit_shapes
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
    # A workspace member: the binaries land in the workspace's target dir, not the crate's.
    $target = (cargo metadata --format-version 1 --no-deps | ConvertFrom-Json).target_directory
} finally { Pop-Location }
$Ingest = Join-Path $target "release/gtfs_ingest.exe"
$Shapes = Join-Path $target "release/transit_shapes.exe"

# --- 2. Mirror -------------------------------------------------------------------------------
# ONE host, fetched serially at a fixed rate. Do not parallelise: --limit-rate is per process, so
# N downloads at once would put N times the load on a volunteer host. Each zip is re-fetched only
# when upstream is newer than the copy on disk (-z), so a re-run pulls just what changed.
if ($SkipMirror) {
    Write-Host "=== [2/5] -SkipMirror: using the zips in $Mirror ==="
} else {
    Write-Host "=== [2/5] mirroring $IndexUrl -> $Mirror ==="
    $index = curl.exe -sS --fail $IndexUrl
    if ($LASTEXITCODE -ne 0) { throw "cannot read $IndexUrl" }
    $names = [string[]]([regex]::Matches(($index -join "`n"), 'href="([^"/]+\.gtfs\.zip)"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if (-not $names) { throw "$IndexUrl lists no .gtfs.zip files" }
    $n = 0; $failed = 0
    foreach ($name in $names) {
        $n++
        $dest = Join-Path $Mirror $name
        $curlArgs = @("-sS", "--fail", "-R", "--limit-rate", "30M", "-o", $dest)
        if (Test-Path $dest) { $curlArgs += @("-z", $dest) }
        curl.exe @curlArgs "$IndexUrl$name"
        # One server error must not lose a 2,000-feed build; the feed count below gates it.
        if ($LASTEXITCODE -ne 0) { $failed++; Write-Warning "download failed: $name" }
        Write-Progress -Activity "Mirror" -Status "$n / $($names.Count)" -PercentComplete (100 * $n / $names.Count)
    }
    Write-Progress -Activity "Mirror" -Completed
    Write-Host "  $($names.Count) zip(s) listed, $failed failed"
}

# --- 3. Unzip --------------------------------------------------------------------------------
# lowercase, every run of non-alphanumerics one `_`: gtfs_ingest's registry::safe_name.
function Get-SafeName([string] $s) { ($s.ToLowerInvariant() -replace '[^a-z0-9]+', '_').Trim('_') }

Write-Host "=== [3/5] unzipping into $Gtfs ==="
# Ordinal name order, so the manifest (and the feed order the pack is merged in) is stable.
$zips = [string[]](Get-ChildItem $Mirror -Filter *.gtfs.zip -File | ForEach-Object Name)
[Array]::Sort($zips, [StringComparer]::Ordinal)
$feeds = $zips | ForEach-Object {
    $stem = $_.Substring(0, $_.Length - ".gtfs.zip".Length)
    $cut = $stem.IndexOf('_')
    $safe = Get-SafeName $stem
    if ($cut -lt 1 -or -not $safe) { Write-Warning "'$stem' has no <region>_<source> split; skipped"; return }
    [pscustomobject]@{
        Zip = Join-Path $Mirror $_
        Name = $safe
        Prefix = $stem.Substring(0, $cut) + "-" + $stem.Substring($cut + 1)
        Dir = Join-Path $Gtfs $safe
    }
}
$feeds | ForEach-Object -ThrottleLimit $Jobs -Parallel {
    $f = $_
    # The stamp names the zip a directory was unzipped from, so a zip the mirror refreshed is
    # unzipped again. A directory with tables but no stamp predates stamps and is trusted.
    $stops = Join-Path $f.Dir "stops.txt"
    $stampFile = Join-Path $f.Dir ".unzipped"
    $zip = Get-Item $f.Zip
    $stamp = "$($zip.Length) $($zip.LastWriteTimeUtc.Ticks)"
    if ((Test-Path $stops) -and (-not (Test-Path $stampFile) -or (Get-Content $stampFile) -eq $stamp)) { return }
    if (Test-Path $f.Dir) { Remove-Item -Recurse -Force $f.Dir }
    New-Item -ItemType Directory -Force -Path $f.Dir | Out-Null
    tar.exe -xf $f.Zip -C $f.Dir 2>$null
    if (-not (Test-Path $stops)) {
        # Some feeds nest their tables one directory deep.
        $inner = Get-ChildItem $f.Dir -Recurse -Filter stops.txt -File | Select-Object -First 1
        if ($inner) { Get-ChildItem $inner.DirectoryName | Move-Item -Destination $f.Dir -Force }
    }
    if (Test-Path $stops) {
        Set-Content -Path $stampFile -Value $stamp
    } else {
        Write-Warning "no stops.txt in $(Split-Path $f.Zip -Leaf); skipped"
        Remove-Item -Recurse -Force $f.Dir
    }
}
$usable = $feeds | Where-Object { Test-Path (Join-Path $_.Dir "stops.txt") }
if (-not $usable) { throw "no usable feeds" }
[System.IO.File]::WriteAllLines($Manifest, [string[]]($usable | ForEach-Object { "$($_.Name)=$($_.Dir)=$($_.Prefix)" }))
Write-Host "  $($usable.Count) feed(s) usable of $($feeds.Count), listed in $Manifest"

# --- 4. Merge --------------------------------------------------------------------------------
Write-Host "=== [4/5] merging into one pack ==="
if (Test-Path $Staging) { Remove-Item -Recurse -Force $Staging }
New-Item -ItemType Directory -Force -Path $Staging | Out-Null
& $Ingest $Staging world --manifest $Manifest
if ($LASTEXITCODE -ne 0) { throw "gtfs_ingest failed with exit code $LASTEXITCODE" }
$pack = Join-Path $Staging "world.transit"

# --- 5. Prove the pack stands in for the feeds, then install it -------------------------------
if ($SkipVerify) {
    Write-Host "=== [5/5] -SkipVerify: not checking the derived transit layer ==="
} else {
    Write-Host "=== [5/5] deriving the transit layer from the feeds and from the pack ==="
    $fromFeeds = Join-Path $Staging "layer.feeds.geojsonseq"
    $fromPack = Join-Path $Staging "layer.pack.geojsonseq"
    & $Shapes $fromFeeds --manifest $Manifest
    if ($LASTEXITCODE -ne 0) { throw "transit_shapes over the feeds failed" }
    & $Shapes $fromPack --pack $pack
    if ($LASTEXITCODE -ne 0) { throw "transit_shapes over the pack failed" }
    if ((Get-FileHash $fromFeeds).Hash -ne (Get-FileHash $fromPack).Hash) {
        throw "the pack does not reproduce the feeds' transit layer; inputs/world.transit was NOT replaced"
    }
    Write-Host "  identical: the pack alone rebuilds the transit layer"
    Remove-Item $fromFeeds, $fromPack
}
Move-Item -Force $pack (Join-Path $Inputs "world.transit")
Move-Item -Force "$pack.json" (Join-Path $Inputs "world.transit.json")
Remove-Item -Recurse -Force $Staging
Get-Content (Join-Path $Inputs "world.transit.json") | Select-Object -First 16
Write-Host "Done: $(Join-Path $Inputs 'world.transit'). $Work is no longer needed by build_graph.ps1."
