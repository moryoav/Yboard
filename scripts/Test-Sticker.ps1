#requires -Version 7.2
[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554',
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [string]$OutputDirectory = 'C:\dev\artifacts\Yboard\sticker-handoff'
)
$ErrorActionPreference = 'Stop'
$projectPath = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if ($OutputDirectory.StartsWith($projectPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep test captures outside the workspace.'
}
$avd = (& $Adb -s $Serial emu avd name | Select-Object -First 1).Trim()
if ($LASTEXITCODE -ne 0 -or $avd -ne 'Yboard_API_36') { throw 'Run this test only on the dedicated Yboard emulator.' }
$ime = & $Adb -s $Serial shell settings get secure default_input_method
if ($ime.Trim() -ne 'com.yboard.keyboard/.ime.YboardIME') { throw 'Select Yboard on the emulator first.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
function Invoke-Adb([string[]]$Commands) {
    $result = & $Adb -s $Serial @Commands
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Commands[0])" }
    return $result
}
function Read-Probe([string]$Name) {
    Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/yboard-sticker-probe.xml') | Out-Null
    $path = Join-Path $OutputDirectory "$Name.xml"
    Invoke-Adb @('pull', '/sdcard/yboard-sticker-probe.xml', $path) | Out-Null
    Invoke-Adb @('shell', 'rm', '/sdcard/yboard-sticker-probe.xml') | Out-Null
    $xml = [xml](Get-Content -LiteralPath $path -Raw)
    $nodes = $xml.SelectNodes("//node[@package='com.yboard.keyboard.test']")
    return @{
        status = ($nodes | Where-Object 'content-desc' -EQ 'Sticker handoff status').text
        draft = ($nodes | Where-Object 'content-desc' -EQ 'Sticker draft').text
        bounds = ($nodes | Where-Object 'content-desc' -EQ 'Sticker draft').bounds
        location = ($nodes | Where-Object 'content-desc' -EQ 'Sticker button location').text
    }
}
$results = foreach ($mode in @('whatsapp', 'both', 'accept', 'wildcard', 'reject', 'none', 'png')) {
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', 'com.yboard.keyboard.test/com.yboard.keyboard.test.StickerProbeActivity',
        '-f', '0x10008000', '--es', 'mode', $mode) | Out-Null
    $before = Read-Probe "$mode-before"
    if ($before.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'The fixture editor is not visible.' }
    $tapX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $tapY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-Adb @('shell', 'input', 'tap', $tapX.ToString(), $tapY.ToString()) | Out-Null
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        $before = Read-Probe "$mode-before"
        if ($before.location -match '^\d+,\d+$') { break }
    }
    if ($before.status -ne 'Waiting for sticker') { throw "Provider permission check failed: $($before.status)" }
    if ($before.location -notmatch '^(\d+),(\d+)$') { throw 'The keyboard did not finish opening.' }
    Invoke-Adb @('shell', 'input', 'tap', $Matches[1], $Matches[2]) | Out-Null
    $after = Read-Probe "$mode-after"
    $expected = switch ($mode) {
        'whatsapp' { 'Received #1 transparent 512x512 WebP' }
        'both' { 'Received #1 transparent 512x512 WebP' }
        'accept' { 'Received #1 transparent 512x512 WebP' }
        'wildcard' { 'Received #1 transparent 512x512 WebP' }
        'reject' { 'Rejected as requested' }
        default { 'Waiting for sticker' }
    }
    if (-not $after.status.StartsWith($expected)) { throw "Handoff failed in ${mode}: $($after.status)" }
    if ($after.draft -ne 'Keep this draft') { throw 'The existing draft was changed.' }
    [pscustomobject]@{ mode = $mode; result = $after.status; draftPreserved = $true }
}
$results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'results.json') -Encoding utf8NoBOM
$results | Format-Table -AutoSize
Write-Output 'All 7 sticker handoff scenarios passed.'
