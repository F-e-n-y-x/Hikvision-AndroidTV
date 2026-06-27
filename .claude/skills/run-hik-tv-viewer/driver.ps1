<#
  driver.ps1 — build, install, launch and DRIVE the Hik TV Viewer Android TV app
  on a connected device/emulator via adb.

  This is the agent harness for the run-hik-tv-viewer skill. It wraps the exact
  adb/gradle commands that were verified against the Television_1080p emulator.

  Examples (run from anywhere; paths resolve to the repo automatically):
    pwsh .claude/skills/run-hik-tv-viewer/driver.ps1 smoke
    pwsh .claude/skills/run-hik-tv-viewer/driver.ps1 build release
    pwsh .claude/skills/run-hik-tv-viewer/driver.ps1 install release
    pwsh .claude/skills/run-hik-tv-viewer/driver.ps1 launch
    pwsh .claude/skills/run-hik-tv-viewer/driver.ps1 key down
    pwsh .claude/skills/run-hik-tv-viewer/driver.ps1 menu
    pwsh .claude/skills/run-hik-tv-viewer/driver.ps1 longkey left   # long-press menu gesture
    pwsh .claude/skills/run-hik-tv-viewer/driver.ps1 shot grid
#>
param(
  [Parameter(Position = 0)] [string] $cmd = "help",
  [Parameter(Position = 1, ValueFromRemainingArguments = $true)] $rest
)
$ErrorActionPreference = "Stop"

$Repo     = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$Sdk      = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
           elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
           else { "$env:LOCALAPPDATA\Android\Sdk" }
$AdbExe   = Join-Path $Sdk "platform-tools\adb.exe"
$Pkg      = "com.hiktv.viewer"
$Activity = "$Pkg/.ui.setup.SetupActivity"
$Shots    = Join-Path $PSScriptRoot "shots"
$Keys     = @{ up=19; down=20; left=21; right=22; center=23; ok=23; back=4; menu=82; chup=166; chdown=167 }

function Get-Serial {
  if ($env:ANDROID_SERIAL) { return $env:ANDROID_SERIAL }
  $line = (& $AdbExe devices) | Select-String "device$" | Where-Object { $_ -notmatch "List of" } | Select-Object -First 1
  if (-not $line) { throw "No device/emulator attached. Start the TV emulator first (see SKILL.md)." }
  return (($line -split "\s+")[0])
}
function Adb { & $AdbExe -s $script:Serial @args }
function Code($k) { if ($Keys.ContainsKey($k)) { $Keys[$k] } else { $k } }

# Resolve the APK for the connected device's CPU (ABI splits produce app-<abi>-<variant>.apk;
# fall back to the universal app-<variant>.apk if splits are disabled).
function Apk($variant) {
  $dir = "$Repo\app\build\outputs\apk\$variant"
  $abi = (Adb shell getprop ro.product.cpu.abi).Trim()
  $split = "$dir\app-$abi-$variant.apk"
  if (Test-Path $split) { return $split }
  return "$dir\app-$variant.apk"
}

if ($cmd -notin @("help","build")) { $script:Serial = Get-Serial }

switch ($cmd) {
  "build" {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
    $variant = if ($rest) { "$rest" } else { "release" }
    $task = "assemble" + $variant.Substring(0,1).ToUpper() + $variant.Substring(1)
    & "$Repo\gradlew.bat" -p $Repo $task
  }
  "install" {
    $variant = if ($rest) { "$rest" } else { "release" }
    $apk = Apk $variant
    if (-not (Test-Path $apk)) { throw "APK missing: $apk  (run: driver.ps1 build $variant)" }
    Adb install -r $apk
  }
  "launch" { Adb shell am start -n $Activity | Out-Null; Write-Host "launched $Activity" }
  "stop"   { Adb shell am force-stop $Pkg; Write-Host "stopped $Pkg" }
  "key"     { Adb shell input keyevent (Code "$($rest)".ToLower()) }
  "longkey" { Adb shell input keyevent --longpress (Code "$($rest)".ToLower()) }
  "menu"    { Adb shell input keyevent 82 }
  "shot" {
    New-Item -ItemType Directory -Force -Path $Shots | Out-Null
    $name = if ($rest) { "$rest" } else { "shot" }
    Adb shell screencap -p /sdcard/_drv.png | Out-Null
    Adb pull /sdcard/_drv.png "$Shots\$name.png" | Out-Null
    Write-Host "saved $Shots\$name.png"
  }
  "smoke" {
    $apk = Apk "release"
    if (-not (Test-Path $apk)) { throw "Build first: driver.ps1 build release" }
    Adb install -r $apk | Out-Null
    Adb shell am start -n $Activity | Out-Null
    Start-Sleep -Seconds 6
    New-Item -ItemType Directory -Force -Path $Shots | Out-Null
    Adb shell screencap -p /sdcard/_drv.png | Out-Null
    Adb pull /sdcard/_drv.png "$Shots\smoke.png" | Out-Null
    Write-Host "smoke OK -> $Shots\smoke.png"
    (Adb shell dumpsys window windows | Select-String "mCurrentFocus")
  }
  default {
    Write-Host "Usage: driver.ps1 <command> [arg]"
    Write-Host "  build [debug|release]      assemble the APK"
    Write-Host "  install [debug|release]    adb install -r"
    Write-Host "  launch | stop             start / force-stop the app"
    Write-Host "  key <up|down|left|right|ok|back|menu|chup|chdown|NN>"
    Write-Host "  longkey <left|...>         long-press a key (e.g. left = open menu)"
    Write-Host "  menu                       open the in-app menu (KEYCODE_MENU)"
    Write-Host "  shot <name>                screenshot -> shots\<name>.png"
    Write-Host "  smoke                      install+launch+screenshot+focus check"
  }
}
