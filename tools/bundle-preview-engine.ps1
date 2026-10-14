param(
    [string]$EngineDirectory = "$env:USERPROFILE\.screenrecorder\ffmpeg\win\bin",
    [string]$Destination = ""
)
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
if (!$Destination) { $Destination = Join-Path $Root "target\portable" }
foreach ($Tool in @("ffmpeg.exe", "ffprobe.exe")) {
    $Source = Join-Path $EngineDirectory $Tool
    if (!(Test-Path $Source -PathType Leaf)) { throw "Missing engine: $Source" }
    & $Source -version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Invalid engine: $Source" }
}
$Bin = Join-Path $Destination "ffmpeg\win"
New-Item $Bin -ItemType Directory -Force | Out-Null
Copy-Item (Join-Path $EngineDirectory "ffmpeg.exe") $Bin -Force
Copy-Item (Join-Path $EngineDirectory "ffprobe.exe") $Bin -Force
Write-Host "Offline engine included in $Bin"
Write-Host "Place the application JAR in $Destination; bundle a Java runtime separately for machines without Java."
Write-Host "Distribution must include the licenses and corresponding source obligations of your FFmpeg build."
