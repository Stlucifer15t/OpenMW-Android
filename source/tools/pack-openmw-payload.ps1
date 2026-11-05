#requires -Version 5.1
<#
.SYNOPSIS
    Packs the generated OpenMW 0.51.0 Android native payload into the zip that
    GitHub Actions downloads before assembling the APK.

.DESCRIPTION
    app/build.gradle refuses to build without a native payload, and that payload
    is produced locally by tools\build-openmw-051-runtime.ps1 (WSL + NDK, tens of
    minutes to hours). CI cannot reproduce it cheaply, so the payload is shipped
    as a release asset instead of a commit.

    This script collects exactly the files the Gradle gate verifies:

        app/src/main/jniLibs/arm64-v8a/*.so
        app/src/main/assets/libopenmw/**
        app/src/main/assets/android_omwfx/*.omwfx
        buildscripts/openmw-051-patch39-libopenmw.sha256

    and writes them to `out\openmw-051-payload.zip` with paths relative to
    `source\`, which is what the workflow unzips into.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\pack-openmw-payload.ps1
#>
param(
    [string]$Output = ''
)

$ErrorActionPreference = 'Stop'

$SourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$AppDir = Join-Path $SourceRoot 'app'

if (-not $Output) {
    $Output = Join-Path $SourceRoot 'out\openmw-051-payload.zip'
}

$ShaFile = Join-Path $SourceRoot 'buildscripts\openmw-051-patch39-libopenmw.sha256'
$Verify = Join-Path $PSScriptRoot 'verify-openmw-payload.py'

Write-Host '==> Checking payload prerequisites'

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    $python = Get-Command python3 -ErrorAction SilentlyContinue
}
if ($python -and (Test-Path $Verify)) {
    & $python.Source $Verify | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw 'The native payload is incomplete; nothing to pack. Fix the items above first.'
    }
}
else {
    Write-Warning 'Python not found; skipping the pre-pack verification.'
}

if (-not (Test-Path $ShaFile)) {
    throw "Missing $ShaFile. Run tools\apply-openmw-051-patch39-release-log-cleanup.ps1 first."
}

$Items = @(
    (Join-Path $AppDir 'src\main\jniLibs\arm64-v8a')
    (Join-Path $AppDir 'src\main\assets\libopenmw')
    (Join-Path $AppDir 'src\main\assets\android_omwfx')
    $ShaFile
)

foreach ($Item in $Items) {
    if (-not (Test-Path $Item)) {
        throw "Missing payload directory/file: $Item"
    }
}

$OutDir = Split-Path -Parent $Output
if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
if (Test-Path $Output) {
    Remove-Item $Output -Force
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Archive = [System.IO.Compression.ZipFile]::Open($Output, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $Count = 0
    $Total = 0L

    foreach ($Item in $Items) {
        $Files = if (Test-Path $Item -PathType Container) {
            Get-ChildItem -Path $Item -Recurse -File
        }
        else {
            Get-Item $Item
        }

        foreach ($File in $Files) {
            # Paths are stored relative to source\ so the workflow can extract
            # the archive directly into source\.
            $Relative = $File.FullName.Substring($SourceRoot.Length).TrimStart('\', '/')
            $EntryName = $Relative -replace '\\', '/'
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $Archive, $File.FullName, $EntryName,
                [System.IO.Compression.CompressionLevel]::Optimal)
            $Count++
            $Total += $File.Length
        }
    }
}
finally {
    $Archive.Dispose()
}

$SizeMb = [math]::Round((Get-Item $Output).Length / 1MB, 1)
Write-Host ''
Write-Host ("Packed {0} files ({1} MB uncompressed) -> {2} ({3} MB)" -f $Count, [math]::Round($Total / 1MB, 1), $Output, $SizeMb)
Write-Host ''
Write-Host 'Next: publish this zip as a release asset named openmw-051-payload.zip'
Write-Host '(see tools\GITHUB_BUILD.md), then run the "Android build" workflow on GitHub.'
