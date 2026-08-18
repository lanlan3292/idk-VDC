# Build vdserver.jar (with DEX) on Windows PowerShell
# Usage:
#   $env:ANDROID_HOME = "D:\fuckgoogle"
#   .\scripts\build_server.ps1

param(
    [string]$SdkDir = ""
)

$ErrorActionPreference = "Continue"

if (-not $SdkDir) { $SdkDir = $env:ANDROID_HOME }
if (-not $SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
if (-not $SdkDir -or -not (Test-Path $SdkDir)) {
    Write-Host "Usage: .\scripts\build_server.ps1 -SdkDir 'D:\path\to\Android\Sdk'"
    exit 1
}

# android.jar
$androidJar = $null
foreach ($api in @(34, 35, 33, 32, 31)) {
    $c = Join-Path $SdkDir "platforms\android-$api\android.jar"
    if (Test-Path $c) { $androidJar = $c; break }
}
if (-not $androidJar) {
    Write-Host "android.jar not found under $SdkDir\platforms"
    exit 1
}

# Find d8 (build-tools)
$d8 = $null
$btRoot = Join-Path $SdkDir "build-tools"
if (Test-Path $btRoot) {
    $versions = Get-ChildItem $btRoot -Directory | Sort-Object Name -Descending
    foreach ($v in $versions) {
        $candidate = Join-Path $v.FullName "d8.bat"
        if (-not (Test-Path $candidate)) { $candidate = Join-Path $v.FullName "d8" }
        if (Test-Path $candidate) { $d8 = $candidate; break }
    }
}
if (-not $d8) {
    Write-Host "d8 not found. Install build-tools, e.g.:"
    Write-Host '  sdkmanager "build-tools;34.0.0"'
    exit 1
}

Write-Host "Java: $(cmd /c 'java -version 2>&1' | Select-Object -First 1)"
Write-Host "android.jar: $androidJar"
Write-Host "d8: $d8"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$src = Join-Path $root "server\src\main\java"
$classes = Join-Path $root "server\build\classes"
$dexDir = Join-Path $root "server\build\dex"
$jarOut = Join-Path $root "server\build\libs"

Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $dexDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
New-Item -ItemType Directory -Force -Path $jarOut | Out-Null

$javaFiles = @(Get-ChildItem -Path $src -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
if ($javaFiles.Count -eq 0) {
    Write-Host "No .java sources under $src"
    exit 1
}

Write-Host "Compiling $($javaFiles.Count) Java sources ..."
$sourcesList = Join-Path $env:TEMP "vd_sources.txt"
$javaFiles | Set-Content -Path $sourcesList -Encoding ASCII

& javac -encoding UTF-8 -source 11 -target 11 -classpath $androidJar -d $classes "@$sourcesList"
if ($LASTEXITCODE -ne 0) {
    Write-Host "javac failed"
    exit 1
}
Remove-Item $sourcesList -ErrorAction SilentlyContinue

# Collect all .class files for d8
$classFiles = @(Get-ChildItem -Path $classes -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
Write-Host "Converting $($classFiles.Count) class files to DEX with d8 ..."

# d8 outputs classes.dex into --output dir
& $d8 --classpath $androidJar --output $dexDir @classFiles
if ($LASTEXITCODE -ne 0) {
    Write-Host "d8 failed"
    exit 1
}

$dexFile = Join-Path $dexDir "classes.dex"
if (-not (Test-Path $dexFile)) {
    Write-Host "classes.dex not produced"
    exit 1
}

# Package jar containing classes.dex (this is what app_process needs)
$jarPath = Join-Path $jarOut "vdserver.jar"
if (Test-Path $jarPath) { Remove-Item $jarPath }
Write-Host "Packaging $jarPath (with classes.dex) ..."
Push-Location $dexDir
& jar cf $jarPath classes.dex
Pop-Location

if (-not (Test-Path $jarPath)) {
    Write-Host "jar packaging failed"
    exit 1
}

$size = (Get-Item $jarPath).Length
Write-Host "Done: $jarPath ($size bytes)"
Write-Host ""
Write-Host "Push and start:"
Write-Host "  adb push $jarPath /data/local/tmp/vdserver.jar"
Write-Host "  adb shell CLASSPATH=/data/local/tmp/vdserver.jar app_process /system/bin com.vdcontroller.server.Server --name=vdcontroller"
