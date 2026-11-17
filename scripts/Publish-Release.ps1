#requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$NotesFile,
    [string]$OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'Yboard-releases'),
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = 'moryoav/Yboard'
$projectPath = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
Set-Location -LiteralPath $projectPath
$NotesFile = (Resolve-Path -LiteralPath $NotesFile).Path
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if ($OutputDirectory.StartsWith($projectPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $OutputDirectory -eq $projectPath) {
    throw 'Keep release artifacts outside the Git workspace.'
}
if (@(Get-Process git -ErrorAction SilentlyContinue).Count -gt 8) { throw 'Too many Git processes are running. Check them before publishing.' }

function Invoke-Git([string[]]$GitArguments) {
    $result = & git @GitArguments
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $($GitArguments -join ' ')" }
    return $result
}
function Invoke-Gh([string[]]$GhArguments) {
    $result = & gh @GhArguments
    if ($LASTEXITCODE -ne 0) { throw "GitHub CLI failed: $($GhArguments[0])" }
    return $result
}

$account = Invoke-Gh @('api', 'user', '--jq', '.login')
if ($account -ne 'moryoav') { throw 'Select the moryoav GitHub CLI account before publishing.' }
if ((Invoke-Git @('branch', '--show-current')) -ne 'main') { throw 'Publish from main.' }
if (Invoke-Git @('status', '--porcelain')) { throw 'Commit and push all source changes before publishing.' }
$origin = Invoke-Git @('remote', 'get-url', 'origin')
if ($origin -notmatch '^https://github\.com/moryoav/Yboard(?:\.git)?$|^git@github\.com:moryoav/Yboard(?:\.git)?$') { throw 'origin does not point to moryoav/Yboard.' }
Invoke-Git @('fetch', '--tags', 'origin') | Out-Null
$head = Invoke-Git @('rev-parse', 'HEAD')
if ($head -ne (Invoke-Git @('rev-parse', 'origin/main'))) { throw 'Local main and origin/main differ. Synchronize them before publishing.' }
$tracked = Invoke-Git @('ls-files')
if ($tracked | Where-Object { $_ -match '(?i)(^|/)(agents\.md|local\.properties|\.env(?:\..*)?)$|\.(apk|keystore|jks|pem|p12|pfx|key)$' }) {
    throw 'Local instructions, APKs or credential files are tracked. Remove them before publishing.'
}

$version = @{}
Get-Content -LiteralPath 'version.properties' | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $version[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$versionName = $version.versionName
$versionCode = [long]$version.versionCode
if ($versionName -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$' -or $versionCode -le 461164) { throw 'Set a new release version in version.properties.' }
$tag = "v$versionName"
$prerelease = $versionName.Contains('-')
$latestJson = & gh api "repos/$repo/releases/latest" 2>$null
if ($LASTEXITCODE -eq 0) {
    $latestRelease = $latestJson | ConvertFrom-Json
    $latestManifest = $latestRelease.assets | Where-Object name -EQ 'update.json'
    if (-not $latestManifest) { throw 'The latest release is missing its update manifest.' }
    $latestVersion = Invoke-RestMethod -Uri $latestManifest.browser_download_url
    if ($versionCode -le [long]$latestVersion.versionCode) { throw 'The new versionCode must exceed the latest public release.' }
}

if (-not $SkipBuild) {
    $wrapper = if ($IsWindows) { '.\gradlew.bat' } else { './gradlew' }
    & $wrapper :app:testDebugUnitTest --tests '*Test' :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Tests or release build failed.' }
}
$apkPath = Join-Path $projectPath 'app/build/outputs/apk/release/app-release.apk'
if (-not (Test-Path -LiteralPath $apkPath)) { throw 'The release APK is missing.' }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Set ANDROID_HOME to the Android SDK.' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Install Android SDK build tools.' }
$aapt = Join-Path $buildTools.FullName $(if ($IsWindows) { 'aapt.exe' } else { 'aapt' })
$signer = Join-Path $buildTools.FullName $(if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' })
$badging = & $aapt dump badging $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the APK.' }
$package = $badging | Select-Object -First 1
if ($package -notmatch "name='com\.yboard\.keyboard' versionCode='(\d+)' versionName='([^']+)'") { throw 'This is not a Yboard APK.' }
if ([long]$Matches[1] -ne $versionCode -or $Matches[2] -ne $versionName) { throw 'The built APK does not match version.properties.' }
$certificates = & $signer verify --print-certs $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
if (-not ($certificates -match 'Signer #1 certificate SHA-256 digest: 23fe3518c9a56b2f290a2c932f4dd8a4bdeea9835f0d1e793a51350525d5b9b4')) {
    throw 'Sign the APK with the original Yboard keystore before publishing.'
}

$releaseDir = Join-Path $OutputDirectory $tag
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
$apkName = "Yboard-$tag.apk"
$publishedApk = Join-Path $releaseDir $apkName
Copy-Item -LiteralPath $apkPath -Destination $publishedApk -Force
$sha256 = (Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestPath = Join-Path $releaseDir 'update.json'
[ordered]@{
    schemaVersion = 1; tag = $tag; applicationId = 'com.yboard.keyboard'
    versionName = $versionName; versionCode = $versionCode; apkName = $apkName
    size = (Get-Item -LiteralPath $publishedApk).Length; sha256 = $sha256
} | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM

# Recheck after building; the release must describe the pushed source exactly.
if (Invoke-Git @('status', '--porcelain')) { throw 'Source changed while building. Commit and push it first.' }
if ($head -ne (Invoke-Git @('rev-parse', 'HEAD'))) { throw 'HEAD changed while building.' }
Invoke-Git @('fetch', 'origin') | Out-Null
if ($head -ne (Invoke-Git @('rev-parse', 'origin/main'))) { throw 'Remote main changed while building.' }
& git show-ref --verify --quiet "refs/tags/$tag"
if ($LASTEXITCODE -eq 0) {
    if ((Invoke-Git @('rev-list', '-n', '1', $tag)) -ne $head) { throw 'The release tag points to different source.' }
} else {
    Invoke-Git @('tag', '-a', $tag, '-m', "Release $versionName") | Out-Null
}
Invoke-Git @('push', 'origin', "refs/tags/$tag") | Out-Null
$existingJson = & gh api "repos/$repo/releases/tags/$tag" 2>$null
if ($LASTEXITCODE -eq 0) {
    $existing = $existingJson | ConvertFrom-Json
    if (-not $existing.draft) { throw 'This release is already published. Bump the version instead of replacing it.' }
    Invoke-Gh @('release', 'upload', $tag, $publishedApk, $manifestPath, '--repo', $repo, '--clobber') | Out-Null
    Invoke-Gh @('release', 'edit', $tag, '--repo', $repo, '--notes-file', $NotesFile) | Out-Null
} else {
    $create = @('release', 'create', $tag, $publishedApk, $manifestPath, '--repo', $repo, '--verify-tag', '--draft', '--title', "Yboard $versionName", '--notes-file', $NotesFile)
    if ($prerelease) { $create += '--prerelease' }
    Invoke-Gh $create | Out-Null
}
$saved = (Invoke-Gh @('api', "repos/$repo/releases/tags/$tag")) | ConvertFrom-Json
$uploadedApk = $saved.assets | Where-Object name -EQ $apkName
if ($uploadedApk.state -ne 'uploaded' -or $uploadedApk.size -ne (Get-Item -LiteralPath $publishedApk).Length -or $uploadedApk.digest -ne "sha256:$sha256") {
    throw 'The uploaded APK did not match the local APK. The release remains a draft.'
}
$uploadedManifest = $saved.assets | Where-Object name -EQ 'update.json'
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($uploadedManifest.state -ne 'uploaded' -or $uploadedManifest.digest -ne "sha256:$manifestHash") {
    throw 'The uploaded manifest did not match the local manifest. The release remains a draft.'
}
if ($saved.performed_via_github_app.slug -eq 'chatgpt-codex-connector') { throw 'Unexpected GitHub posting identity.' }
$latest = if ($prerelease) { '--latest=false' } else { '--latest=true' }
Invoke-Gh @('release', 'edit', $tag, '--repo', $repo, '--draft=false', $latest) | Out-Null
$published = (Invoke-Gh @('api', "repos/$repo/releases/tags/$tag")) | ConvertFrom-Json
if ($published.draft -or $published.prerelease -ne $prerelease) { throw 'The release was not published with the expected visibility.' }
if ($published.body -ne (Get-Content -LiteralPath $NotesFile -Raw).TrimEnd() -and $published.body.TrimEnd() -ne (Get-Content -LiteralPath $NotesFile -Raw).TrimEnd()) { throw 'Verify the saved release notes.' }
Write-Output "Published $($published.html_url)"
Write-Output "APK: $publishedApk"
