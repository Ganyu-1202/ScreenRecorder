param([string]$Version = "1.0.4", [string]$JarPath = "")
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$Cache = Join-Path $PSScriptRoot "packaging-cache"
$JPackage = "D:\Program Files\Java\jdk-21\bin\jpackage.exe"
$Engine = Get-Item (Join-Path $Root "ffmpeg\win")
$Jdk = Get-ChildItem (Join-Path $Cache "temurin-jdk") -Directory | Select-Object -First 1
if (!$Engine -or !$Jdk) { throw "Provide project ffmpeg/win and Temurin JDK in tools/packaging-cache first." }
$Jar = if ($JarPath) { $JarPath } else { Join-Path $Root "target\ScreenRecorder.jar" }
if (!(Test-Path $Jar)) { throw "Run mvn -Pfatjar package first." }
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$Build = Join-Path $Root "target\installer-$Stamp"
$InputDir = Join-Path $Build "input"
$ImageDir = Join-Path $Build "image"
$OutputDir = Join-Path $Build "output"
$RuntimePath = Join-Path $Build "runtime"
& (Join-Path $Jdk.FullName "bin\jlink.exe") --add-modules java.base,java.desktop,java.logging,java.net.http,java.sql,java.xml,java.naming,jdk.unsupported,jdk.unsupported.desktop,jdk.crypto.ec,jdk.localedata --include-locales en,zh --strip-debug --no-header-files --no-man-pages --compress zip-6 --output $RuntimePath
if ($LASTEXITCODE -ne 0) { throw "Runtime build failed" }
$Bin = Join-Path $InputDir "ffmpeg\win"
New-Item $Bin -ItemType Directory -Force | Out-Null
Copy-Item $Jar $InputDir
Get-ChildItem $Engine.FullName -File | Where-Object { $_.Extension -eq ".dll" -or $_.Name -in @("ffmpeg.exe", "ffprobe.exe", "wasapi-loopback.exe") } | Copy-Item -Destination $Bin
Copy-Item (Join-Path $Engine.FullName "LICENSE-FFmpeg.txt") (Join-Path $Bin "LICENSE-FFmpeg.txt")
Copy-Item (Join-Path $Root "tools\build-custom-ffmpeg.sh") (Join-Path $Bin "build-custom-ffmpeg.sh")
$LicenseDir = Join-Path $Bin "licenses"
New-Item $LicenseDir -ItemType Directory -Force | Out-Null
Get-ChildItem (Join-Path $Engine.FullName "licenses") -Directory | Copy-Item -Destination $LicenseDir -Recurse
$Notice = @"
ScreenRecorder $Version - Windows x64 offline test distribution
Includes compact Temurin Java 21 runtime and locally built FFmpeg 9.0.2 GPL shared build.
This engine supports local files/pipes only; network URL input and unrelated output encoders are intentionally disabled.
Build configuration is included in app/ffmpeg/win/build-custom-ffmpeg.sh.
Java runtime licenses are included under runtime/legal.
FFmpeg license is included under app/ffmpeg/win/LICENSE-FFmpeg.txt.
FFmpeg build source/recipes: https://github.com/BtbN/FFmpeg-Builds
FFmpeg upstream: https://ffmpeg.org
This build uses GPL components (including x264/x265). Before public distribution,
provide corresponding source for the exact binary and third-party components in
accordance with their licenses; upstream URLs alone are not a substitute.
This installer is unsigned and has not been verified on a clean Windows VM.
"@
Set-Content (Join-Path $InputDir "DISTRIBUTION-NOTICE.txt") $Notice -Encoding UTF8
$Icon = Join-Path $Root "src\main\resources\org\firefly\screenrec\icons\screen-recorder.ico"
& $JPackage --type app-image --name ScreenRecorder --app-version $Version --input $InputDir --main-jar ScreenRecorder.jar --main-class org.firefly.screenrec.Launcher --runtime-image $RuntimePath --icon $Icon --dest $ImageDir --vendor ScreenRecorder
if ($LASTEXITCODE -ne 0) { throw "Application image build failed" }
$env:PATH = (Join-Path $Cache "wix") + ";" + $env:PATH
& $JPackage --type exe --name ScreenRecorder --app-version $Version --app-image (Join-Path $ImageDir "ScreenRecorder") --dest $OutputDir --vendor ScreenRecorder --win-dir-chooser --win-menu --win-shortcut --win-upgrade-uuid 843c266b-6d3b-49cb-8cb3-34408306dfe9
if ($LASTEXITCODE -ne 0) { throw "Installer build failed" }
Write-Host "Application image: $(Join-Path $ImageDir 'ScreenRecorder')"
Write-Host "Installer: $OutputDir"
