<# :
@echo off
setlocal
where pwsh >nul 2>nul
if %errorlevel%==0 ( set "PSBIN=pwsh" ) else ( set "PSBIN=powershell" )
%PSBIN% -NoProfile -ExecutionPolicy Bypass -Command "$env:_BATROOT='%~dp0'; $env:_BATPATH='%~f0'; & ([scriptblock]::Create((Get-Content -Raw '%~f0'))) %*"
exit /b %errorlevel%
: end batch #>
# test.bat - Run Rust + unit + instrumented tests without installing apps.
# (PowerShell twin of ./test.)
#   test.bat <module> [module...]                             # all three phases
#   test.bat --rust-only <module> [module...]                 # cargo tests only
#   test.bat --unit-only <module> [module...]                 # gradle unit tests only
#   test.bat --instrumented-only [--device S] <module>        # on-device tests only
#   test.bat all [flags]                                      # every app + library module
#   test.bat --dry-run calculator                             # preview, runs nothing
#
# Module notation mirrors check.bat: slash == colon, games:/personal: shorthand,
# 'all' keyword, deduped. Tasks run with --continue so one broken module does not
# block the rest; exit code is nonzero if anything failed.
#
# RUST (skipped by --unit-only/--instrumented-only): workspace members together
# via -p from the repo root, standalone crates (own [workspace]) individually
# in their own dir. Coverage on (default) uses cargo llvm-cov; when coverage
# tools cannot be installed (for example offline) the step warns cleanly and
# falls back to plain cargo test. --no-coverage runs plain cargo test --locked.
#
# UNIT (skipped by --rust-only/--instrumented-only): app modules run
# :mod:createDevUnitTestCoverageReport, library modules run
# :mod:createDebugUnitTestCoverageReport (single gradlew invocation,
# --continue; app vs lib via common-conventions-app). --no-coverage runs
# :mod:testDevUnitTest / :mod:testDebugUnitTest instead. Afterwards each
# build/reports/coverage/test/*/report.xml is parsed as XML (counter type
# INSTRUCTION) printing per-module covered/missed plus a TOTAL.
#
# INSTRUMENTED (skipped by --rust-only/--unit-only): scope is the in-scope
# modules having any *.kt under src/androidTest. One gradlew invocation runs
# :mod:assembleDev :mod:assembleDevAndroidTest with --continue. Each app APK is
# session-installed (with .idsig sidecar, never plain adb install); each test
# APK uses plain adb install -r. Then am instrument runs the runner, with
# coverage pulled back to build/outputs/coverage-manual when enabled.
$ErrorActionPreference = 'Stop'

$TestRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:_BATROOT.TrimEnd('\') }

function Show-TestHelp {
  @'
Usage:
  test.bat [--rust-only|--unit-only|--instrumented-only] [--no-coverage] [--device S] [--dry-run] [--help] <module> [module...]
  test.bat all [flags]
  test.bat calculator
  test.bat --unit-only calculator maps
  test.bat --rust-only games/voxels
  test.bat --instrumented-only --device 9 calculator
  test.bat --dry-run calculator

Module notation mirrors check.bat: slash == colon, games:/personal: shorthand,
'all' keyword, deduped. Tasks run with --continue so one broken module does not
block the rest; exit code is nonzero if anything failed.

Phases (all run unless one scope flag selects a single phase):
  RUST: workspace members together via -p from the repo root, standalone
    crates individually in their own dir. Coverage on (default) runs
    cargo llvm-cov --workspace --locked --exclude tilecodec --lcov
    --output-path target/llvm-cov.lcov (with -p flags for in-scope crates when
    not 'all') plus per standalone dir lcov. Missing coverage tools warn
    cleanly and fall back to plain cargo test. --no-coverage runs plain
    cargo test --locked equivalents.
  UNIT: app modules -> :mod:createDevUnitTestCoverageReport, library modules
    -> :mod:createDebugUnitTestCoverageReport (single gradlew invocation,
    --continue). --no-coverage -> :mod:testDevUnitTest / :mod:testDebugUnitTest.
    Afterwards each build/reports/coverage/test/*/report.xml is parsed as XML
    (counter type INSTRUCTION) printing per-module covered/missed + TOTAL.
  INSTRUMENTED: scope = in-scope modules having any *.kt under src/androidTest.
    One invocation runs :mod:assembleDev :mod:assembleDevAndroidTest with
    --continue, then each app APK is session-installed (with .idsig sidecar,
    never plain adb install), each test APK uses plain adb install -r, then
    am instrument runs <pkg>.test/androidx.test.runner.AndroidJUnitRunner with
    coverage pulled back to build/outputs/coverage-manual.

Device: --device S / --device=S selects a serial, or 8|9 for Pixel 8 / Pixel 9
  (bare 8|9 also accepted); default is the first non-emulator device. No device
  is a hard error for instrumented runs unless --dry-run.

When 'all' expands, Gradle uses the big-build heap (same as install.bat all).
--dry-run prints modules/tasks/commands and runs nothing.
'@
}

function Normalize-TestModule([string]$M) {
  $M = $M.Trim()
  $M = [regex]::Replace($M, '^[/:]+', '')
  $M = [regex]::Replace($M, '[/:]+$', '')
  $M = $M.Replace('/', ':')
  while ($M.Contains('::')) { $M = $M.Replace('::', ':') }
  return $M.ToLowerInvariant()
}

function Get-TestAllModuleDirs {
  $TestSettings = Join-Path $TestRoot 'settings.gradle.kts'
  $TestFound = @()
  if (-not (Test-Path -LiteralPath $TestSettings)) { return $TestFound }
  foreach ($TestLine in [System.IO.File]::ReadLines($TestSettings)) {
    if ($TestLine -match 'include\(":([^"]+)"\)') {
      $TestP = $Matches[1].Replace(':', '/')
      if (-not $TestP) { continue }
      $TestBuild = Join-Path $TestRoot "$TestP/build.gradle.kts"
      if ((Test-Path -LiteralPath $TestBuild) -and
          (Select-String -LiteralPath $TestBuild -Pattern 'common-conventions-(app|library)' -Quiet)) {
        $TestFound += $TestP
      }
    }
  }
  return @($TestFound | Sort-Object -Unique)
}

function Get-TestCargoBin {
  $TestCargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
  if ($TestCargoCmd) { return $TestCargoCmd.Source }
  $TestCargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $HOME '.cargo' }
  $TestCand = Join-Path $TestCargoHome 'bin/cargo'
  if (Test-Path -LiteralPath $TestCand -PathType Leaf) { return $TestCand }
  $TestCandExe = "$TestCand.exe"
  if (Test-Path -LiteralPath $TestCandExe -PathType Leaf) { return $TestCandExe }
  return ''
}

function Get-TestAdb {
  $TestAdbCmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($TestAdbCmd) { return $TestAdbCmd.Source }
  $TestSdkBases = @()
  if ($env:ANDROID_HOME) { $TestSdkBases += $env:ANDROID_HOME }
  if ($env:ANDROID_SDK_ROOT) { $TestSdkBases += $env:ANDROID_SDK_ROOT }
  $TestSdkBases += (Join-Path $HOME 'Android/Sdk')
  $TestSdkBases += (Join-Path $HOME 'Library/Android/sdk')
  if ($env:LOCALAPPDATA) { $TestSdkBases += (Join-Path $env:LOCALAPPDATA 'Android/Sdk') }
  foreach ($TestBase in $TestSdkBases) {
    if (-not $TestBase) { continue }
    foreach ($TestName in @('platform-tools/adb', 'platform-tools/adb.exe')) {
      $TestCand = Join-Path $TestBase $TestName
      if (Test-Path -LiteralPath $TestCand -PathType Leaf) { return $TestCand }
    }
  }
  return ''
}

function Get-TestApplicationId([string]$Mod) {
  $TestBuild = Join-Path $TestRoot "$($Mod.Replace(':', '/'))/build.gradle.kts"
  if (-not (Test-Path -LiteralPath $TestBuild)) { return '' }
  foreach ($TestLine in [System.IO.File]::ReadLines($TestBuild)) {
    if ($TestLine -match '^\s*applicationId\s*=\s*"([^"]+)"') { return $Matches[1] }
  }
  return ''
}

function Get-TestBuiltApk([string]$Mod, [string]$VariantLc) {
  $TestFs = $Mod.Replace(':', '/')
  $TestOutDir = Join-Path $TestRoot "$TestFs/build/outputs/apk/$VariantLc"
  $TestMeta = Join-Path $TestOutDir 'output-metadata.json'
  $TestBaseName = ($Mod -split ':')[-1]
  $TestFileName = "$TestBaseName-$VariantLc.apk"
  if (Test-Path -LiteralPath $TestMeta -PathType Leaf) {
    try {
      $TestMetaText = [System.IO.File]::ReadAllText($TestMeta)
      $TestM = [regex]::Match($TestMetaText, '"outputFile"\s*:\s*"([^"]+)"')
      if ($TestM.Success -and $TestM.Groups[1].Value) { $TestFileName = $TestM.Groups[1].Value }
    } catch {
      Write-Output "Warning: could not read output-metadata.json for $Mod; using fallback name."
    }
  }
  $TestApk = Join-Path $TestOutDir $TestFileName
  if (Test-Path -LiteralPath $TestApk -PathType Leaf) { return $TestApk }
  return ''
}

function Get-TestAndroidTestApk([string]$Mod) {
  $TestFs = $Mod.Replace(':', '/')
  $TestDir = Join-Path $TestRoot "$TestFs/build/outputs/apk/androidTest/dev"
  if (-not (Test-Path -LiteralPath $TestDir)) { return '' }
  try {
    $TestApks = @(Get-ChildItem -LiteralPath $TestDir -Filter '*.apk' -File -ErrorAction SilentlyContinue | Sort-Object Name)
  } catch {
    return ''
  }
  if ($TestApks.Count -eq 0) { return '' }
  return $TestApks[0].FullName
}

function Test-ApkViaSession([string]$Apk, [string]$PackageId, [string]$AdbBin, [string]$Serial, [string]$Cur = '', [string]$Total = '') {
  $TestApkName = Split-Path -Leaf $Apk
  $TestApkSize = (Get-Item -LiteralPath $Apk).Length
  $TestApkMb = '{0:N1}' -f ($TestApkSize / 1MB)
  $TestDeviceApk = "/data/local/tmp/install_${PackageId}.apk"
  $TestPrefix = ''
  if ($Cur -and $Total) { $TestPrefix = "[$Cur/$Total] " }
  Write-Output "$TestPrefix Installing $PackageId ($TestApkName, ${TestApkMb} MB)..."
  Write-Output "  $TestPrefix[1/4] Pushing APK to device..."
  $TestPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & $AdbBin -s $Serial push $Apk $TestDeviceApk >$null 2>&1
    if ($LASTEXITCODE -ne 0) {
      Write-Output "  $TestPrefix Error: adb push failed for $TestApkName."
      return $false
    }
    Write-Output "  $TestPrefix[2/4] Creating install session..."
    $TestCreateOut = & $AdbBin -s $Serial shell pm install-create -r 2>&1
    $TestSession = ''
    $TestSm = [regex]::Match(($TestCreateOut -join "`n"), 'created install session \[([0-9]+)\]')
    if ($TestSm.Success) { $TestSession = $TestSm.Groups[1].Value }
    if (-not $TestSession) {
      Write-Output "  $TestPrefix Error: could not create install session."
      & $AdbBin -s $Serial shell rm -f $TestDeviceApk >$null 2>&1
      return $false
    }
    Write-Output "  $TestPrefix[3/4] Staging APK into session $TestSession..."
    & $AdbBin -s $Serial shell pm install-write -S $TestApkSize $TestSession base.apk $TestDeviceApk >$null 2>&1
    if ($LASTEXITCODE -ne 0) {
      Write-Output "  $TestPrefix Error: failed to stage $TestApkName into session $TestSession."
      & $AdbBin -s $Serial shell pm install-abandon $TestSession >$null 2>&1
      & $AdbBin -s $Serial shell rm -f $TestDeviceApk >$null 2>&1
      return $false
    }
    $TestIdsig = "$Apk.idsig"
    if (Test-Path -LiteralPath $TestIdsig -PathType Leaf) {
      $TestDeviceIdsig = "$TestDeviceApk.idsig"
      Write-Output "  $TestPrefix Staging .idsig sidecar..."
      & $AdbBin -s $Serial push $TestIdsig $TestDeviceIdsig >$null 2>&1
      if ($LASTEXITCODE -eq 0) {
        $TestIdsigSize = (Get-Item -LiteralPath $TestIdsig).Length
        & $AdbBin -s $Serial shell pm install-write -S $TestIdsigSize $TestSession base.apk.idsig $TestDeviceIdsig >$null 2>&1
        if ($LASTEXITCODE -ne 0) {
          Write-Output "  $TestPrefix Warning: could not stage .idsig; system-app updates may fail fs-verity."
        }
      }
      else {
        Write-Output "  $TestPrefix Warning: could not push .idsig; system-app updates may fail fs-verity."
      }
      & $AdbBin -s $Serial shell rm -f $TestDeviceIdsig >$null 2>&1
    }
    else {
      Write-Output "  $TestPrefix Warning: no .idsig sidecar next to the APK; system-app updates may fail fs-verity."
    }
    Write-Output "  $TestPrefix[4/4] Committing install session $TestSession..."
    $TestCommitOut = & $AdbBin -s $Serial shell pm install-commit $TestSession 2>&1
    & $AdbBin -s $Serial shell rm -f $TestDeviceApk >$null 2>&1
    if (-not (($TestCommitOut -join "`n") -match '(?im)^Success')) {
      Write-Output "  $TestPrefix Error: install failed for ${PackageId}: $($TestCommitOut -join ' ')"
      return $false
    }
    if ($Cur -and $Total) {
      Write-Output "  Installed $PackageId. [$Cur/$Total done]"
    }
    else {
      Write-Output "  Installed $PackageId."
    }
    return $true
  } catch {
    Write-Output "  $TestPrefix Warning: session install hit an error for $PackageId : $($_.Exception.Message)"
    return $false
  } finally {
    $ErrorActionPreference = $TestPrevPref
  }
}

if ($args.Count -eq 0) {
  Show-TestHelp
  Write-Output ''
  Write-Output 'Error: no modules specified.'
  exit 1
}
foreach ($TestA in $args) {
  if ($TestA -eq '--help' -or $TestA -eq '-h' -or $TestA -eq 'help') {
    Show-TestHelp
    exit 0
  }
}

$TestRustOnly = $false
$TestUnitOnly = $false
$TestInstrOnly = $false
$TestNoCoverage = $false
$TestDryRun = ($env:TEST_DRY_RUN -eq '1')
$TestDeviceHint = ''
$TestModulesRaw = @()
$TestIdx = 0
while ($TestIdx -lt $args.Count) {
  $TestArg = [string]$args[$TestIdx]
  $TestLc = $TestArg.ToLowerInvariant()
  if ($TestLc -eq '--rust-only') { $TestRustOnly = $true; $TestIdx++; continue }
  elseif ($TestLc -eq '--unit-only') { $TestUnitOnly = $true; $TestIdx++; continue }
  elseif ($TestLc -eq '--instrumented-only') { $TestInstrOnly = $true; $TestIdx++; continue }
  elseif ($TestLc -eq '--no-coverage') { $TestNoCoverage = $true; $TestIdx++; continue }
  elseif ($TestLc -eq '--dry-run') { $TestDryRun = $true; $TestIdx++; continue }
  elseif ($TestLc -eq '--help' -or $TestLc -eq '-h' -or $TestLc -eq 'help') {
    Show-TestHelp
    exit 0
  }
  elseif ($TestLc -eq '--device') {
    if ($TestIdx + 1 -ge $args.Count) {
      Write-Output 'Error: --device requires a value (serial, 8, or 9).'
      exit 1
    }
    $TestDeviceHint = [string]$args[$TestIdx + 1]
    $TestIdx += 2
    continue
  }
  elseif ($TestLc -like '--device=*') {
    $TestDeviceHint = $TestArg.Substring('--device='.Length)
    if (-not $TestDeviceHint) {
      Write-Output 'Error: --device requires a value (serial, 8, or 9).'
      exit 1
    }
    $TestIdx++
    continue
  }
  elseif ($TestLc -eq '8' -or $TestLc -eq '9') {
    $TestDeviceHint = $TestLc
    $TestIdx++
    continue
  }
  elseif ($TestLc -match '^[0-9]+$') {
    Write-Output "Error: Invalid device '$TestArg'. Use a serial, 8 (Pixel 8), or 9 (Pixel 9)."
    exit 1
  }
  elseif ($TestLc.StartsWith('--')) {
    Write-Output "Error: unknown flag '$TestArg'."
    Show-TestHelp
    exit 1
  }
  else {
    $TestModulesRaw += $TestArg
    $TestIdx++
  }
}

$TestScopeCount = 0
if ($TestRustOnly) { $TestScopeCount++ }
if ($TestUnitOnly) { $TestScopeCount++ }
if ($TestInstrOnly) { $TestScopeCount++ }
if ($TestScopeCount -gt 1) {
  Write-Output 'Error: --rust-only, --unit-only, and --instrumented-only are mutually exclusive.'
  exit 1
}

$TestMods = @()
$TestAllExpanded = $false
foreach ($TestRaw in $TestModulesRaw) {
  $TestNorm = Normalize-TestModule ([string]$TestRaw)
  if (-not $TestNorm) { continue }
  if ($TestNorm -eq 'all') {
    if (-not $TestAllExpanded) {
      foreach ($TestSm in (Get-TestAllModuleDirs)) {
        if ($TestSm -and ($TestMods -notcontains $TestSm)) { $TestMods += $TestSm }
      }
      $TestAllExpanded = $true
    }
    continue
  }
  if (-not $TestNorm.Contains(':')) {
    foreach ($TestPfx in @('games', 'personal')) {
      if (Test-Path -LiteralPath (Join-Path $TestRoot "$TestPfx/$TestNorm/build.gradle.kts")) {
        $TestNorm = "$TestPfx`:$TestNorm"
        break
      }
    }
  }
  $TestFs = $TestNorm.Replace(':', '/')
  if (-not (Test-Path -LiteralPath (Join-Path $TestRoot "$TestFs/build.gradle.kts"))) {
    [Console]::Error.WriteLine("Error: unknown module '$TestRaw' (-> $TestFs/, no build.gradle.kts).")
    exit 1
  }
  if ($TestMods -notcontains $TestFs) { $TestMods += $TestFs }
}
if ($TestMods.Count -eq 0) {
  [Console]::Error.WriteLine('Error: no valid modules.')
  exit 1
}

$RunRust = (-not $TestUnitOnly) -and (-not $TestInstrOnly)
$RunUnit = (-not $TestRustOnly) -and (-not $TestInstrOnly)
$RunInstr = (-not $TestRustOnly) -and (-not $TestUnitOnly)

$TestRootCrates = @()
$TestStandaloneDirs = @()
if ($RunRust) {
  foreach ($TestMod in $TestMods) {
    try {
      $TestRdir = Join-Path $TestRoot "$TestMod/src/main/rust"
      $TestCt = Join-Path $TestRdir 'Cargo.toml'
      if (Test-Path -LiteralPath $TestCt -PathType Leaf) {
        if (Select-String -LiteralPath $TestCt -Pattern '^\[workspace\]\s*$' -Quiet) {
          if ($TestStandaloneDirs -notcontains $TestRdir) { $TestStandaloneDirs += $TestRdir }
        }
        else {
          $TestName = $null
          foreach ($TestLine in [System.IO.File]::ReadLines($TestCt)) {
            if ($TestLine -match '^\s*name\s*=\s*"([^"]+)"') { $TestName = $Matches[1]; break }
          }
          if ($TestName -and ($TestRootCrates -notcontains $TestName)) { $TestRootCrates += $TestName }
        }
      }
    } catch {
      Write-Output "Warning: could not inspect Rust crate for $TestMod : $($_.Exception.Message)"
    }
  }
}

$TestUnitTasks = @()
$TestIsApp = @{}
if ($RunUnit) {
  foreach ($TestMod in $TestMods) {
    $TestGmod = $TestMod.Replace('/', ':')
    $TestBuildFile = Join-Path $TestRoot "$TestMod/build.gradle.kts"
    $TestApp = $false
    try {
      if ((Test-Path -LiteralPath $TestBuildFile) -and (Select-String -LiteralPath $TestBuildFile -Pattern 'common-conventions-app' -Quiet)) {
        $TestApp = $true
      }
    } catch {
      $TestApp = $false
    }
    $TestIsApp[$TestMod] = $TestApp
    if ($TestNoCoverage) {
      if ($TestApp) { $TestUnitTasks += ":${TestGmod}:testDevUnitTest" }
      else { $TestUnitTasks += ":${TestGmod}:testDebugUnitTest" }
    }
    else {
      if ($TestApp) { $TestUnitTasks += ":${TestGmod}:createDevUnitTestCoverageReport" }
      else { $TestUnitTasks += ":${TestGmod}:createDebugUnitTestCoverageReport" }
    }
  }
}

$TestInstrMods = @()
if ($RunInstr) {
  foreach ($TestMod in $TestMods) {
    try {
      $TestAtDir = Join-Path $TestRoot "$TestMod/src/androidTest"
      if (-not (Test-Path -LiteralPath $TestAtDir)) { continue }
      $TestKt = Get-ChildItem -LiteralPath $TestAtDir -Filter '*.kt' -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($TestKt) { $TestInstrMods += $TestMod }
    } catch {
      Write-Output "Warning: could not inspect androidTest for $TestMod : $($_.Exception.Message)"
    }
  }
}
$TestInstrTasks = @()
foreach ($TestMod in $TestInstrMods) {
  $TestGmod = $TestMod.Replace('/', ':')
  $TestInstrTasks += ":${TestGmod}:assembleDev"
  $TestInstrTasks += ":${TestGmod}:assembleDevAndroidTest"
}

$TestAdbBin = Get-TestAdb
$TestTargetSerial = ''
if ($TestAdbBin) {
  $TestPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $TestDevLines = & $TestAdbBin devices -l 2>$null
  } catch {
    $TestDevLines = @()
    Write-Output 'Warning: adb devices query failed; continuing without a selected device.'
  }
  $ErrorActionPreference = $TestPrevPref
  foreach ($TestDevLine in @($TestDevLines)) {
    $TestParts = ([string]$TestDevLine -split '\s+')
    if ($TestParts.Count -lt 2) { continue }
    $TestDevSerial = $TestParts[0]
    $TestDevState = $TestParts[1]
    if ($TestDevState -ne 'device') { continue }
    if ($TestDevSerial -like 'emulator*') { continue }
    if ($TestDevSerial -eq 'List') { continue }
    if (-not $TestDeviceHint) {
      $TestTargetSerial = $TestDevSerial
      break
    }
    $TestDevModel = ''
    $TestModelM = [regex]::Match([string]$TestDevLine, 'model:([^\s]+)')
    if ($TestModelM.Success) { $TestDevModel = $TestModelM.Groups[1].Value }
    if (($TestDeviceHint -eq '8') -and ($TestDevModel -eq 'Pixel_8')) {
      $TestTargetSerial = $TestDevSerial
      break
    }
    if (($TestDeviceHint -eq '9') -and ($TestDevModel -like 'Pixel_9*')) {
      $TestTargetSerial = $TestDevSerial
      break
    }
    if (($TestDeviceHint -ne '8') -and ($TestDeviceHint -ne '9') -and ($TestDevSerial -eq $TestDeviceHint)) {
      $TestTargetSerial = $TestDevSerial
      break
    }
  }
}

$TestNeedsDevice = ($RunInstr -and ($TestInstrMods.Count -gt 0))
if ($TestNeedsDevice) {
  if (-not $TestAdbBin) {
    if ($TestDryRun) {
      Write-Output '[DRY-RUN] Warning: adb not found; a device could not be selected.'
    }
    else {
      Write-Output 'Error: adb not found; cannot select a target device.'
      Write-Output 'Install Android platform-tools and put adb on PATH (or set ANDROID_HOME).'
      exit 1
    }
  }
  elseif ($TestTargetSerial) {
    $env:ANDROID_SERIAL = $TestTargetSerial
    if ($TestDeviceHint -eq '8' -or $TestDeviceHint -eq '9') {
      Write-Output "Target device: $($env:ANDROID_SERIAL) (Pixel $TestDeviceHint)"
    }
    elseif ($TestDeviceHint) {
      Write-Output "Target device: $($env:ANDROID_SERIAL) (selected via --device)"
    }
    else {
      Write-Output "Target device: $($env:ANDROID_SERIAL) (first connected device not starting with 'emulator')"
    }
  }
  elseif ($TestDeviceHint) {
    Write-Output "Error: No connected device matching '$TestDeviceHint' found (check 'adb devices')."
    exit 1
  }
  elseif ($TestDryRun) {
    Write-Output '[DRY-RUN] Warning: no non-emulator device connected; ANDROID_SERIAL not set.'
  }
  else {
    Write-Output 'Error: No non-emulator device connected.'
    Write-Output "Connect a physical device (check 'adb devices'); refusing to test on an emulator."
    exit 1
  }
}
elseif ($TestTargetSerial) {
  $env:ANDROID_SERIAL = $TestTargetSerial
  Write-Output "Target device: $($env:ANDROID_SERIAL) (first connected device not starting with 'emulator')"
}

$TestGradleBase = @('--continue')
$TestGradleBaseStr = ' --continue'
if ($TestAllExpanded) {
  $TestGradleBase += '-Dorg.gradle.jvmargs=-Xmx46144m -Xms4512m -XX:MaxMetaspaceSize=1024m -XX:+UseG1GC -Dfile.encoding=UTF-8'
  $TestGradleBase += '--max-workers=32'
  $TestGradleBaseStr += ' -Dorg.gradle.jvmargs=<46g-big-build-heap> --max-workers=32'
}

$TestRepoVersion = 0
$TestNeedBump = 0
$TestVersionOverrideArg = ''
if ($TestNeedsDevice -and $TestTargetSerial -and (-not $TestDryRun)) {
  try {
    $TestRepoVersion = [int]([System.IO.File]::ReadLines((Join-Path $TestRoot 'version.txt')) | Select-Object -First 1).Trim()
  } catch {
    Write-Output 'Warning: could not read version.txt; skipping versionCodeOverride computation.'
    $TestRepoVersion = 0
  }
  if ($TestRepoVersion -gt 0) {
    $TestNeedBump = $TestRepoVersion
    $TestPrevPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
      foreach ($TestMod in $TestInstrMods) {
        try {
          $TestPkg = Get-TestApplicationId $TestMod
          if (-not $TestPkg) { continue }
          $TestListOut = & $TestAdbBin -s $TestTargetSerial shell pm list packages --show-versioncode $TestPkg 2>$null
          $TestDevV = 0
          foreach ($TestListLine in @($TestListOut)) {
            $TestVm = [regex]::Match([string]$TestListLine, 'versionCode:([0-9]+)')
            if ($TestVm.Success) { $TestDevV = [int]$TestVm.Groups[1].Value; break }
          }
          if (($TestDevV -gt 0) -and ($TestDevV -ge $TestNeedBump)) {
            $TestSysOut = & $TestAdbBin -s $TestTargetSerial shell pm list packages -s $TestPkg 2>$null
            $TestIsSystem = $false
            foreach ($TestSysLine in @($TestSysOut)) {
              if ([string]$TestSysLine.Trim() -eq "package:$TestPkg") { $TestIsSystem = $true; break }
            }
            if ($TestIsSystem) { $TestTarget = $TestDevV + 1; $TestKind = 'system app' }
            else { $TestTarget = $TestDevV; $TestKind = 'user app' }
            if ($TestTarget -gt $TestNeedBump) {
              $TestNeedBump = $TestTarget
              Write-Output "Info: $TestPkg is a $TestKind at versionCode $TestDevV on-device (>= repo $TestRepoVersion); will build with -PversionCodeOverride=$TestTarget."
            }
          }
        } catch {
          Write-Output "Warning: versionCode check failed for $TestMod; continuing without override for it."
        }
      }
    } finally {
      $ErrorActionPreference = $TestPrevPref
    }
    if ($TestNeedBump -gt $TestRepoVersion) {
      $TestVersionOverrideArg = "-PversionCodeOverride=$TestNeedBump"
    }
  }
}

$TestUnitArgs = @() + $TestGradleBase
$TestUnitArgsStr = $TestGradleBaseStr
$TestInstrArgs = @() + $TestGradleBase
$TestInstrArgsStr = $TestGradleBaseStr
if ($TestVersionOverrideArg) {
  $TestInstrArgs += $TestVersionOverrideArg
  $TestInstrArgsStr += " $TestVersionOverrideArg"
}

$TestGradlewBat = Join-Path $TestRoot 'gradlew.bat'
$TestGradlewSh = Join-Path $TestRoot 'gradlew'
$TestGradlewDisp = if (Test-Path -LiteralPath $TestGradlewBat) { $TestGradlewBat } else { $TestGradlewSh }

if ($TestDryRun) {
  Write-Output "Modules ($($TestMods.Count)): $($TestMods -join ' ')"
  if ($TestAllExpanded) { Write-Output '[DRY-RUN] all expanded: big-build heap args will be used.' }
  if ($TestDeviceHint) { Write-Output "[DRY-RUN] Device hint: $TestDeviceHint" }
  if ($TestTargetSerial) { Write-Output "[DRY-RUN] Target device: $TestTargetSerial" }
  Write-Output "[DRY-RUN] Coverage: $(if ($TestNoCoverage) { 'off (--no-coverage)' } else { 'on' })"
  Write-Output ''
  if ($RunRust) {
    if (($TestRootCrates.Count -eq 0) -and ($TestStandaloneDirs.Count -eq 0)) {
      Write-Output '[DRY-RUN] Rust: no crates in scope; cargo step skipped.'
    }
    else {
      if ($TestRootCrates.Count -gt 0) {
        $TestPFlags = @()
        foreach ($TestC in $TestRootCrates) { $TestPFlags += @('-p', $TestC) }
        if ($TestNoCoverage) {
          if ($TestAllExpanded) { Write-Output "[DRY-RUN] Would execute: cargo test --locked --workspace (in repo root)" }
          else { Write-Output "[DRY-RUN] Would execute: cargo test --locked $($TestPFlags -join ' ') (in repo root)" }
        }
        else {
          if ($TestAllExpanded) { Write-Output '[DRY-RUN] Would execute: cargo llvm-cov --workspace --locked --exclude tilecodec --lcov --output-path target/llvm-cov.lcov (in repo root)' }
          else { Write-Output "[DRY-RUN] Would execute: cargo llvm-cov --workspace --locked --exclude tilecodec $($TestPFlags -join ' ') --lcov --output-path target/llvm-cov.lcov (in repo root)" }
        }
      }
      foreach ($TestRdir in $TestStandaloneDirs) {
        if ($TestNoCoverage) { Write-Output "[DRY-RUN] Would execute: cargo test --locked (in $TestRdir)" }
        else { Write-Output "[DRY-RUN] Would execute: cargo llvm-cov --locked --lcov --output-path target/llvm-cov.lcov (in $TestRdir)" }
      }
    }
    Write-Output ''
  }
  if ($RunUnit) {
    Write-Output "[DRY-RUN] Unit tasks: $($TestUnitTasks -join ' ')"
    Write-Output "[DRY-RUN] Would execute: $TestGradlewDisp $($TestUnitTasks -join ' ')$TestUnitArgsStr"
    Write-Output '[DRY-RUN] Would parse: <module>/build/reports/coverage/test/*/report.xml (INSTRUCTION counters)'
    Write-Output ''
  }
  if ($RunInstr) {
    if ($TestInstrMods.Count -eq 0) {
      Write-Output '[DRY-RUN] Instrumented: no modules with src/androidTest *.kt in scope; step skipped.'
    }
    else {
      Write-Output "[DRY-RUN] Instrumented modules ($($TestInstrMods.Count)): $($TestInstrMods -join ' ')"
      Write-Output "[DRY-RUN] Would execute: $TestGradlewDisp $($TestInstrTasks -join ' ')$TestInstrArgsStr"
      foreach ($TestMod in $TestInstrMods) {
        $TestPkg = Get-TestApplicationId $TestMod
        $TestFs = $TestMod.Replace(':', '/')
        $TestAppApk = Get-TestBuiltApk $TestMod 'dev'
        $TestTApk = Get-TestAndroidTestApk $TestMod
        if ($TestAppApk) { Write-Output "[DRY-RUN] Would session-install $TestPkg from $TestAppApk (with .idsig sidecar)" }
        else { Write-Output "[DRY-RUN] Would session-install $TestPkg (APK expected after assembleDev in $TestFs/build/outputs/apk/dev)" }
        if ($TestTApk) { Write-Output "[DRY-RUN] Would run: adb install -r $TestTApk" }
        else { Write-Output "[DRY-RUN] Would run: adb install -r $TestFs/build/outputs/apk/androidTest/dev/<test-apk> (expected after assembleDevAndroidTest)" }
        if ($TestPkg) {
          if ($TestNoCoverage) { Write-Output "[DRY-RUN] Would run: adb -s <serial> shell am instrument -w -r $TestPkg.test/androidx.test.runner.AndroidJUnitRunner" }
          else {
            Write-Output "[DRY-RUN] Would run: adb -s <serial> shell am instrument -w -r -e coverage true -e coverageFilePath /data/data/$TestPkg/files/coverage.ec $TestPkg.test/androidx.test.runner.AndroidJUnitRunner"
            Write-Output "[DRY-RUN] Would pull: adb -s <serial> exec-out run-as $TestPkg cat files/coverage.ec -> $TestFs/build/outputs/coverage-manual/$TestPkg.ec"
          }
        }
      }
    }
    Write-Output ''
  }
  exit 0
}

Write-Output "Modules ($($TestMods.Count)): $($TestMods -join ' ')"
if ($RunUnit -and ($TestUnitTasks.Count -gt 0)) { Write-Output "Unit tasks: $($TestUnitTasks -join ' ')" }
if ($RunInstr -and ($TestInstrTasks.Count -gt 0)) { Write-Output "Instrumented tasks: $($TestInstrTasks -join ' ')" }
Write-Output ''

$TestFailures = 0

if ($RunRust) {
  if (($TestRootCrates.Count -eq 0) -and ($TestStandaloneDirs.Count -eq 0)) {
    Write-Output 'No Rust crates in scope; cargo step skipped.'
  }
  else {
    foreach ($TestRc in $TestRootCrates) { Write-Output "Rust: workspace crate $TestRc" }
    foreach ($TestRdir in $TestStandaloneDirs) { Write-Output "Rust: standalone crate ($TestRdir)" }
    $TestCargoBin = Get-TestCargoBin
    if (-not $TestCargoBin) {
      Write-Output 'Warning: cargo not found (install Rust or set CARGO_HOME); Rust step skipped.'
      $TestFailures++
    }
    else {
      $TestCovOk = (-not $TestNoCoverage)
      if ($TestCovOk) {
        try {
          $TestPrevPref = $ErrorActionPreference
          $ErrorActionPreference = 'Continue'
          try {
            & rustup component add llvm-tools-preview 2>&1 | Out-Null
            $TestRustupRc = $LASTEXITCODE
          } catch {
            $TestRustupRc = 1
          }
          if ($TestRustupRc -ne 0) { throw "rustup component add failed (exit $TestRustupRc)" }
          try {
            & $TestCargoBin install cargo-llvm-cov --locked 2>&1 | Out-Null
            $TestCovInstallRc = $LASTEXITCODE
          } catch {
            $TestCovInstallRc = 1
          }
          if ($TestCovInstallRc -ne 0) { throw "cargo install cargo-llvm-cov failed (exit $TestCovInstallRc)" }
          $ErrorActionPreference = $TestPrevPref
        } catch {
          Write-Output "Warning: coverage tools unavailable ($($_.Exception.Message)); running plain cargo test instead."
          $TestCovOk = $false
          $ErrorActionPreference = $TestPrevPref
        }
      }
      if ($TestCovOk) {
        if ($TestRootCrates.Count -gt 0) {
          Write-Output ''
          $TestPFlags = @()
          foreach ($TestC in $TestRootCrates) { $TestPFlags += @('-p', $TestC) }
          try {
            $TestPrevPref = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            Push-Location -LiteralPath $TestRoot
            try {
              if ($TestAllExpanded) {
                Write-Output 'Running: cargo llvm-cov --workspace --locked --exclude tilecodec --lcov --output-path target/llvm-cov.lcov'
                & $TestCargoBin llvm-cov --workspace --locked --exclude tilecodec --lcov --output-path target/llvm-cov.lcov
              }
              else {
                Write-Output "Running: cargo llvm-cov --workspace --locked --exclude tilecodec $($TestPFlags -join ' ') --lcov --output-path target/llvm-cov.lcov"
                & $TestCargoBin llvm-cov --workspace --locked --exclude tilecodec @TestPFlags --lcov --output-path target/llvm-cov.lcov
              }
              $TestRc2 = $LASTEXITCODE
            } finally {
              Pop-Location
              $ErrorActionPreference = $TestPrevPref
            }
            if ($TestRc2 -ne 0) {
              Write-Output "Warning: cargo llvm-cov (workspace) reported failures (exit $TestRc2)."
              $TestFailures++
            }
          } catch {
            Write-Output "Warning: cargo llvm-cov (workspace) hit an error : $($_.Exception.Message)"
            $TestFailures++
          }
        }
        foreach ($TestRdir in $TestStandaloneDirs) {
          Write-Output ''
          Write-Output "Running: cargo llvm-cov --locked --lcov --output-path target/llvm-cov.lcov (in $TestRdir)"
          try {
            $TestPrevPref = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            Push-Location -LiteralPath $TestRdir
            try {
              & $TestCargoBin llvm-cov --locked --lcov --output-path target/llvm-cov.lcov
              $TestRc2 = $LASTEXITCODE
            } finally {
              Pop-Location
              $ErrorActionPreference = $TestPrevPref
            }
            if ($TestRc2 -ne 0) {
              Write-Output "Warning: cargo llvm-cov ($TestRdir) reported failures (exit $TestRc2)."
              $TestFailures++
            }
          } catch {
            Write-Output "Warning: cargo llvm-cov ($TestRdir) hit an error : $($_.Exception.Message)"
            $TestFailures++
          }
        }
      }
      else {
        if ($TestRootCrates.Count -gt 0) {
          Write-Output ''
          $TestPFlags = @()
          foreach ($TestC in $TestRootCrates) { $TestPFlags += @('-p', $TestC) }
          try {
            $TestPrevPref = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            Push-Location -LiteralPath $TestRoot
            try {
              if ($TestAllExpanded) {
                Write-Output 'Running: cargo test --locked --workspace'
                & $TestCargoBin test --locked --workspace
              }
              else {
                Write-Output "Running: cargo test --locked $($TestPFlags -join ' ')"
                & $TestCargoBin test --locked @TestPFlags
              }
              $TestRc2 = $LASTEXITCODE
            } finally {
              Pop-Location
              $ErrorActionPreference = $TestPrevPref
            }
            if ($TestRc2 -ne 0) {
              Write-Output "Warning: cargo test (workspace) reported failures (exit $TestRc2)."
              $TestFailures++
            }
          } catch {
            Write-Output "Warning: cargo test (workspace) hit an error : $($_.Exception.Message)"
            $TestFailures++
          }
        }
        foreach ($TestRdir in $TestStandaloneDirs) {
          Write-Output ''
          Write-Output "Running: cargo test --locked (in $TestRdir)"
          try {
            $TestPrevPref = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            Push-Location -LiteralPath $TestRdir
            try {
              & $TestCargoBin test --locked
              $TestRc2 = $LASTEXITCODE
            } finally {
              Pop-Location
              $ErrorActionPreference = $TestPrevPref
            }
            if ($TestRc2 -ne 0) {
              Write-Output "Warning: cargo test ($TestRdir) reported failures (exit $TestRc2)."
              $TestFailures++
            }
          } catch {
            Write-Output "Warning: cargo test ($TestRdir) hit an error : $($_.Exception.Message)"
            $TestFailures++
          }
        }
      }
    }
  }
  Write-Output ''
}

if ($RunUnit) {
  Write-Output "Running unit tests: $($TestUnitTasks -join ' ')"
  $TestBashCmd = Get-Command bash -ErrorAction SilentlyContinue
  $TestUnitExit = 0
  try {
    $TestPrevPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
      if (Test-Path -LiteralPath $TestGradlewBat) {
        Write-Output "Running: $TestGradlewBat $($TestUnitTasks -join ' ')$TestUnitArgsStr"
        Write-Output ''
        & $TestGradlewBat @TestUnitTasks @TestUnitArgs
        $TestUnitExit = $LASTEXITCODE
      }
      elseif ($TestBashCmd -and (Test-Path -LiteralPath $TestGradlewSh)) {
        Write-Output "Running: $TestGradlewSh $($TestUnitTasks -join ' ')$TestUnitArgsStr"
        Write-Output ''
        & $TestBashCmd.Source $TestGradlewSh @TestUnitTasks @TestUnitArgs
        $TestUnitExit = $LASTEXITCODE
      }
      else {
        Write-Output 'Error: no Gradle wrapper found (gradlew.bat / gradlew).'
        $TestUnitExit = 1
      }
    } finally {
      $ErrorActionPreference = $TestPrevPref
    }
  } catch {
    Write-Output "Warning: unit-test invocation hit an error : $($_.Exception.Message)"
    $TestUnitExit = 1
  }
  Write-Output ''
  if ($TestUnitExit -ne 0) {
    Write-Output "Warning: unit tests reported failures (exit $TestUnitExit)."
    $TestFailures++
  }
  if (-not $TestNoCoverage) {
    $TestTotalCovered = 0
    $TestTotalMissed = 0
    foreach ($TestMod in $TestMods) {
      try {
        $TestCovBase = Join-Path $TestRoot "$TestMod/build/reports/coverage/test"
        if (-not (Test-Path -LiteralPath $TestCovBase)) {
          Write-Output "Warning: $TestMod no coverage data (no tests?)"
          continue
        }
        $TestReports = @(Get-ChildItem -Path $TestCovBase -Filter 'report.xml' -Recurse -File -ErrorAction SilentlyContinue)
        if ($TestReports.Count -eq 0) {
          Write-Output "Warning: $TestMod no coverage data (no tests?)"
          continue
        }
        $TestModCovered = 0
        $TestModMissed = 0
        foreach ($TestRep in $TestReports) {
          try {
            [xml]$TestXml = Get-Content -LiteralPath $TestRep.FullName
            $TestCounters = @($TestXml.report.counter | Where-Object { $_.type -eq 'INSTRUCTION' })
            if ($TestCounters.Count -gt 0) {
              $TestModCovered += [int]$TestCounters[0].covered
              $TestModMissed += [int]$TestCounters[0].missed
            }
            else {
              $TestNode = $TestXml.SelectSingleNode("//counter[@type='INSTRUCTION']")
              if ($TestNode) {
                $TestModCovered += [int]$TestNode.covered
                $TestModMissed += [int]$TestNode.missed
              }
            }
          } catch {
            Write-Output "Warning: could not parse $($TestRep.FullName); skipping it."
          }
        }
        Write-Output "Coverage $TestMod : covered $TestModCovered missed $TestModMissed"
        $TestTotalCovered += $TestModCovered
        $TestTotalMissed += $TestModMissed
      } catch {
        Write-Output "Warning: coverage parse failed for $TestMod; continuing."
      }
    }
    Write-Output "Coverage TOTAL : covered $TestTotalCovered missed $TestTotalMissed"
  }
  Write-Output ''
}

if ($RunInstr) {
  if ($TestInstrMods.Count -eq 0) {
    Write-Output 'No instrumented tests in scope (no src/androidTest *.kt); instrumented step skipped.'
  }
  else {
    Write-Output "Running instrumented assemble: $($TestInstrTasks -join ' ')"
    $TestBashCmd2 = Get-Command bash -ErrorAction SilentlyContinue
    $TestInstrBuildExit = 0
    try {
      $TestPrevPref = $ErrorActionPreference
      $ErrorActionPreference = 'Continue'
      try {
        if (Test-Path -LiteralPath $TestGradlewBat) {
          Write-Output "Running: $TestGradlewBat $($TestInstrTasks -join ' ')$TestInstrArgsStr"
          Write-Output ''
          & $TestGradlewBat @TestInstrTasks @TestInstrArgs
          $TestInstrBuildExit = $LASTEXITCODE
        }
        elseif ($TestBashCmd2 -and (Test-Path -LiteralPath $TestGradlewSh)) {
          Write-Output "Running: $TestGradlewSh $($TestInstrTasks -join ' ')$TestInstrArgsStr"
          Write-Output ''
          & $TestBashCmd2.Source $TestGradlewSh @TestInstrTasks @TestInstrArgs
          $TestInstrBuildExit = $LASTEXITCODE
        }
        else {
          Write-Output 'Error: no Gradle wrapper found (gradlew.bat / gradlew).'
          $TestInstrBuildExit = 1
        }
      } finally {
        $ErrorActionPreference = $TestPrevPref
      }
    } catch {
      Write-Output "Warning: instrumented assemble hit an error : $($_.Exception.Message)"
      $TestInstrBuildExit = 1
    }
    Write-Output ''
    if ($TestInstrBuildExit -ne 0) {
      Write-Output 'Warning: assemble reported failures; running instrumented tests for whatever APKs exist.'
      $TestFailures++
    }
    $TestTotal = $TestInstrMods.Count
    Write-Output ''
    Write-Output "Running instrumented tests for $TestTotal module(s) on $TestTargetSerial..."
    $TestCur = 0
    foreach ($TestMod in $TestInstrMods) {
      $TestCur++
      try {
        $TestPkg = Get-TestApplicationId $TestMod
        if (-not $TestPkg) {
          Write-Output "[$TestCur/$TestTotal] Error: no applicationId found for $TestMod."
          $TestFailures++
          continue
        }
        $TestAppApk = Get-TestBuiltApk $TestMod 'dev'
        if (-not $TestAppApk) {
          Write-Output "[$TestCur/$TestTotal] Error: no app APK found for $TestMod (assemble failed?)."
          $TestFailures++
          continue
        }
        $TestTApk = Get-TestAndroidTestApk $TestMod
        if (-not $TestTApk) {
          Write-Output "[$TestCur/$TestTotal] Error: no test APK found for $TestMod (assembleDevAndroidTest failed?)."
          $TestFailures++
          continue
        }
        if (-not (Test-ApkViaSession $TestAppApk $TestPkg $TestAdbBin $TestTargetSerial "$TestCur" "$TestTotal")) {
          $TestFailures++
          continue
        }
        Write-Output "[$TestCur/$TestTotal] Installing test APK for $TestPkg..."
        $TestPrevPref = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
          & $TestAdbBin -s $TestTargetSerial install -r $TestTApk 2>&1 | Out-Null
          $TestTestInstallRc = $LASTEXITCODE
        } catch {
          $TestTestInstallRc = 1
        } finally {
          $ErrorActionPreference = $TestPrevPref
        }
        if ($TestTestInstallRc -ne 0) {
          Write-Output "[$TestCur/$TestTotal] Error: test APK install failed for $TestPkg."
          $TestFailures++
          continue
        }
        $TestRunner = "$TestPkg.test/androidx.test.runner.AndroidJUnitRunner"
        $TestPrevPref = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
          if ($TestNoCoverage) {
            Write-Output "[$TestCur/$TestTotal] Running: am instrument -w -r $TestRunner"
            $TestInstrOut = & $TestAdbBin -s $TestTargetSerial shell am instrument -w -r $TestRunner 2>&1
          }
          else {
            $TestCovDevicePath = "/data/data/$TestPkg/files/coverage.ec"
            Write-Output "[$TestCur/$TestTotal] Running: am instrument -w -r -e coverage true $TestRunner"
            $TestInstrOut = & $TestAdbBin -s $TestTargetSerial shell am instrument -w -r -e coverage true -e coverageFilePath $TestCovDevicePath $TestRunner 2>&1
          }
          $TestInstrRc = $LASTEXITCODE
          $TestInstrText = ($TestInstrOut -join "`n")
        } catch {
          $TestInstrOut = @("$($_.Exception.Message)")
          $TestInstrText = ($TestInstrOut -join "`n")
          $TestInstrRc = 1
        } finally {
          $ErrorActionPreference = $TestPrevPref
        }
        $TestInstrFailed = $false
        if ($TestInstrRc -ne 0) { $TestInstrFailed = $true }
        if ($TestInstrText -match '(?im)^(FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE:\s*0)') { $TestInstrFailed = $true }
        if ($TestInstrText -match '(?im)FAILURES!!!') { $TestInstrFailed = $true }
        Write-Output $TestInstrText
        if ($TestInstrFailed) {
          Write-Output "[$TestCur/$TestTotal] Error: instrumented tests failed for $TestPkg."
          $TestFailures++
          continue
        }
        if (-not $TestNoCoverage) {
          try {
            $TestFsPath = $TestMod.Replace(':', '/')
            $TestCovOutDir = Join-Path $TestRoot "$TestFsPath/build/outputs/coverage-manual"
            try { New-Item -ItemType Directory -Force -Path $TestCovOutDir >$null 2>&1 } catch { }
            $TestCovOutFile = Join-Path $TestCovOutDir "$TestPkg.ec"
            $TestPrevPref = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
              & $TestAdbBin -s $TestTargetSerial exec-out run-as $TestPkg cat files/coverage.ec > $TestCovOutFile 2>$null
              $TestPullRc = $LASTEXITCODE
            } catch {
              $TestPullRc = 1
            } finally {
              $ErrorActionPreference = $TestPrevPref
            }
            if (($TestPullRc -eq 0) -and (Test-Path -LiteralPath $TestCovOutFile -PathType Leaf)) {
              Write-Output "Coverage pulled: $TestCovOutFile"
            }
            else {
              Write-Output "Warning: could not pull coverage.ec for $TestPkg."
            }
          } catch {
            Write-Output "Warning: coverage pull failed for $TestPkg; continuing."
          }
        }
        Write-Output "[$TestCur/$TestTotal] Instrumented tests passed for $TestPkg."
      } catch {
        Write-Output "[$TestCur/$TestTotal] Warning: instrumented run hit an error for $TestMod : $($_.Exception.Message)"
        $TestFailures++
      }
    }
  }
  Write-Output ''
}

if ($TestFailures -gt 0) {
  Write-Output "Test complete: failures in $TestFailures step(s)."
  exit 1
}
Write-Output 'Test complete: all executed steps succeeded.'
exit 0
