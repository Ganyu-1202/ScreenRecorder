param([string]$Gcc = "gcc", [string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = "Stop"
if (!$Jdk) { throw "Set JAVA_HOME to a JDK first." }
$Root = Split-Path $PSScriptRoot -Parent
& $Gcc -shared -O2 -static-libgcc "-I$Jdk/include" "-I$Jdk/include/win32" (Join-Path $Root "native/windows/capture-exclusion.c") -o (Join-Path $Root "ffmpeg/win/screenrec-capture.dll") -luser32
if ($LASTEXITCODE -ne 0) { throw "Native capture bridge build failed" }
$Manifest = Join-Path $Root "ffmpeg/win/checksums.json"
$Hashes = Get-Content $Manifest -Raw | ConvertFrom-Json
$Hashes | Add-Member -NotePropertyName "screenrec-capture.dll" -NotePropertyValue ((Get-FileHash (Join-Path $Root "ffmpeg/win/screenrec-capture.dll") -Algorithm SHA256).Hash.ToLower()) -Force
$Hashes | ConvertTo-Json | Set-Content $Manifest -Encoding utf8
