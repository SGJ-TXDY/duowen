# UI-04B device evidence helper (adb only; does not build or change the project).
# Usage: powershell -ExecutionPolicy Bypass -File device.ps1 <command> [arg]
#   info              record model / Android / screen / font scale / navigation / dark mode / installed app
#   install           verify the test APK hash, then adb install -r (keeps app data; back up first)
#   shot <name>       screenshot + UI hierarchy into .\device\<name>.png / .xml
#   tap <text>        tap the first node whose text or content-desc equals <text>
#   back              system back key
#   swipe <up|down>   scroll the screen centre
param([Parameter(Position = 0, Mandatory = $true)][string]$Command, [Parameter(Position = 1)][string]$Arg)
$ErrorActionPreference = 'Stop'

$adb = if ($env:EQ_ADB) { $env:EQ_ADB } else { 'C:\Users\woziji\Documents\Codex\2026-10-21\embeddedquiz-app-embeddedquiz-android-app-linux-5\work\toolchain\sdk\platform-tools\adb.exe' }
$apk = Join-Path $PSScriptRoot '..\..\..\EmbeddedQuiz-1.6.1-ui-mascot-20261106-debug.apk'
$apkHash = '0902931645EAAD68DB3BD982B0BE4FC5D22C1E3167229607A1A4AB1ED4525F92'
$pkg = 'cn.embeddedquiz'
$out = Join-Path $PSScriptRoot 'device'

function Adb { & $adb @args; if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed ($LASTEXITCODE)" } }
function Sh([string]$cmd) { (Adb shell $cmd | Out-String).Trim() }

$devices = @(Adb devices | Select-String "`tdevice$")
if ($devices.Count -ne 1) { throw "need exactly one authorised device, found $($devices.Count); check USB debugging and the authorisation prompt" }
New-Item -ItemType Directory -Force $out | Out-Null

function Dump([string]$file) {
    Sh 'uiautomator dump /sdcard/eq-ui.xml' | Out-Null
    Adb pull /sdcard/eq-ui.xml $file | Out-Null
    [xml](Get-Content -Raw -Encoding UTF8 $file)
}

switch ($Command) {
    'info' {
        $nav = Sh 'settings get secure navigation_mode'
        $navName = switch ($nav) { '0' { '3-button' } '1' { '2-button' } '2' { 'gesture' } default { "unknown" } }
        $lines = @(
            "time=$(Get-Date -Format s)",
            "model=$(Sh 'getprop ro.product.manufacturer') $(Sh 'getprop ro.product.model')",
            "android=$(Sh 'getprop ro.build.version.release') (sdk $(Sh 'getprop ro.build.version.sdk')), build=$(Sh 'getprop ro.build.display.id')",
            "wm size: $(Sh 'wm size' -replace "`r?`n", '; ')",
            "wm density: $(Sh 'wm density' -replace "`r?`n", '; ')",
            "font_scale=$(Sh 'settings get system font_scale')",
            "navigation_mode=$nav ($navName)",
            "night: $(Sh 'cmd uimode night')",
            "installed: $(Sh "dumpsys package $pkg | grep -E 'versionCode|versionName|lastUpdateTime' | head -3" -replace "`r?`n", '; ')"
        )
        $lines | Tee-Object (Join-Path $out ("device-info-{0}.txt" -f (Get-Date -Format 'HHmmss')))
    }
    'install' {
        $h = (Get-FileHash -Algorithm SHA256 $apk).Hash
        if ($h -ne $apkHash) { throw "APK hash mismatch: $h" }
        "APK SHA256 ok: $h"
        Adb install -r $apk
        Sh "dumpsys package $pkg | grep -E 'versionName|lastUpdateTime'"
    }
    'shot' {
        if (-not $Arg) { throw 'shot needs a name' }
        Sh 'screencap -p /sdcard/eq-shot.png' | Out-Null
        Adb pull /sdcard/eq-shot.png (Join-Path $out "$Arg.png") | Out-Null
        try { Dump (Join-Path $out "$Arg.xml") | Out-Null } catch { "ui dump skipped: $($_.Exception.Message)" }
        "saved device\$Arg.png"
    }
    'tap' {
        $doc = Dump (Join-Path $env:TEMP 'eq-ui.xml')
        $node = $doc.SelectNodes('//node') | Where-Object { $_.text -eq $Arg -or $_.'content-desc' -eq $Arg } | Select-Object -First 1
        if (-not $node) { throw "no node with text/content-desc '$Arg'" }
        $b = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
        $x = [int](($b[0] + $b[2]) / 2); $y = [int](($b[1] + $b[3]) / 2)
        Sh "input tap $x $y" | Out-Null
        "tapped '$Arg' at $x,$y bounds=$($node.bounds)"
    }
    'back' { Sh 'input keyevent 4' | Out-Null; 'back' }
    'swipe' {
        $size = [regex]::Matches((Sh 'wm size'), '(\d+)x(\d+)') | Select-Object -Last 1
        $w = [int]$size.Groups[1].Value; $hgt = [int]$size.Groups[2].Value
        $x = [int]($w / 2); $from = [int]($hgt * 0.7); $to = [int]($hgt * 0.3)
        if ($Arg -eq 'down') { $t = $from; $from = $to; $to = $t }
        Sh "input swipe $x $from $x $to 300" | Out-Null
        "swipe $Arg"
    }
    default { throw "unknown command $Command" }
}
