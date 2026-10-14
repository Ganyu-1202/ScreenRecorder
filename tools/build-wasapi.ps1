param([string]$Cxx = "g++")
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
& $Cxx -std=c++17 -O2 -municode -static (Join-Path $Root "native/windows/wasapi-loopback.cpp") -o (Join-Path $Root "ffmpeg/win/wasapi-loopback.exe") -lole32 -luuid -lpropsys
if ($LASTEXITCODE -ne 0) { throw "WASAPI build failed" }
$Manifest = Join-Path $Root "ffmpeg/win/checksums.json"
$Hashes = Get-Content $Manifest -Raw | ConvertFrom-Json
$Hashes | Add-Member -NotePropertyName "wasapi-loopback.exe" -NotePropertyValue ((Get-FileHash (Join-Path $Root "ffmpeg/win/wasapi-loopback.exe") -Algorithm SHA256).Hash.ToLower()) -Force
$Hashes | ConvertTo-Json | Set-Content $Manifest -Encoding utf8
