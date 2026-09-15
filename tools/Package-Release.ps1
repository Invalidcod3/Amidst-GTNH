#requires -Version 7.0
param(
    [ValidatePattern('^$|^[a-z0-9]+(?:-[a-z0-9]+)*$')]
    [string]$PackageSuffix = '',
    [string]$ReleaseNotes = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    $metadata = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo 'src/main/resources/amidst/metadata.properties')))
    $viewerName = $metadata['amidst.build.filename'] + '.jar'
    if ($metadata['amidst.build.filename'] -notmatch '-(v\d+(?:\.\d+)*)$') { throw 'Missing release suffix' }
    $tag = $Matches[1]
    $packageTag = if ($PackageSuffix) { "$tag-$PackageSuffix" } else { $tag }
    if (-not $ReleaseNotes) { $ReleaseNotes = "docs/release-$tag.md" }
    if ($ReleaseNotes -notmatch '^docs/[a-z0-9.-]+\.md$' -or -not (Test-Path -LiteralPath $ReleaseNotes -PathType Leaf)) {
        throw 'ReleaseNotes must name an existing Markdown file in docs/'
    }
    $workerName = "amidst-gtnh-worker-$tag.jar"
    $releaseVersion = $metadata['amidst.release.version']
    $protocol = [int]$metadata['amidst.worker.protocol']
    if ($protocol -le 0) { throw 'Missing worker protocol in metadata' }
    $packageDir = Join-Path $repo "build/Amidst-GTNH-$packageTag"
    $zipPath = "$packageDir.zip"
    $sourceZipPath = "$packageDir-source.zip"
    $assetSumsPath = Join-Path $repo "build/Amidst-GTNH-$packageTag-SHA256SUMS.txt"
    foreach ($path in @($packageDir, $zipPath, $sourceZipPath, $assetSumsPath)) {
        if (Test-Path -LiteralPath $path) { throw "Release output already exists: $path" }
    }
    $testResults = foreach ($project in @('.', 'gtnh-worker')) {
        $total=0; $failures=0; $errors=0; $skipped=0
        foreach ($file in Get-ChildItem -LiteralPath "$project/build/test-results/test" -Filter 'TEST-*.xml') {
            [xml]$report = [IO.File]::ReadAllText($file.FullName)
            $total += [int]$report.testsuite.tests
            $failures += [int]$report.testsuite.failures
            $errors += [int]$report.testsuite.errors
            $skipped += [int]$report.testsuite.skipped
        }
        if ($total -eq 0 -or $failures -ne 0 -or $errors -ne 0) { throw "Tests did not pass: $project" }
        [ordered]@{project=$project; passed=$total-$skipped; skipped=$skipped; failures=$failures; errors=$errors}
    }
    function Read-ZipText($path, $entry) {
        $archive = [IO.Compression.ZipFile]::OpenRead($path)
        try {
            $reader = [IO.StreamReader]::new($archive.GetEntry($entry).Open())
            try { $reader.ReadToEnd() } finally { $reader.Dispose() }
        } finally { $archive.Dispose() }
    }
    $viewerPath = Join-Path $repo "build/release/$viewerName"
    $workerPath = Join-Path $repo "build/release/$workerName"
    $packagedMetadata = ConvertFrom-StringData (Read-ZipText $viewerPath 'amidst/metadata.properties')
    if ($packagedMetadata['amidst.build.filename'] -ne $metadata['amidst.build.filename'] -or
        $packagedMetadata['amidst.release.version'] -ne $releaseVersion) { throw 'Viewer version mismatch' }
    $workerInfo = Read-ZipText $workerPath 'mcmod.info' | ConvertFrom-Json
    if ($workerInfo.modList[0].version -ne $releaseVersion) { throw 'Worker version mismatch' }
    $workerManifest = Read-ZipText $workerPath 'META-INF/MANIFEST.MF'
    if ($workerManifest -notmatch 'FMLCorePluginContainsFMLMod: true' -or
        $workerManifest -notmatch ('Implementation-Version: ' + [regex]::Escape($releaseVersion))) { throw 'Worker manifest mismatch' }
    foreach ($jar in @($viewerPath, $workerPath)) {
        $manifest = Read-ZipText $jar 'META-INF/MANIFEST.MF'
        if ($manifest -notmatch "(?m)^Amidst-Worker-Protocol: $protocol\r?$" ) {
            throw "Packaged protocol mismatch: $jar"
        }
    }

    New-Item -ItemType Directory -Path $packageDir | Out-Null
    Copy-Item -LiteralPath $viewerPath,$workerPath -Destination $packageDir
    foreach ($file in @('LICENSE', 'src/main/resources/licenses/VisualProspecting-LICENSE.txt', 'src/main/resources/licenses/VisualProspecting-NOTICE.txt')) {
        Copy-Item -LiteralPath $file -Destination $packageDir
    }
    Copy-Item -LiteralPath (Join-Path $repo 'docs') -Destination (Join-Path $packageDir 'docs') -Recurse
    Copy-Item -LiteralPath 'README.md', 'BUILDING.md', 'CONTRIBUTING.md' -Destination $packageDir
    # These linked build notes belong to the source package; preserve their relative paths.
    New-Item -ItemType Directory -Path (Join-Path $packageDir 'gtnh-worker/tools') -Force | Out-Null
    Copy-Item -LiteralPath 'gtnh-worker/README.md' -Destination (Join-Path $packageDir 'gtnh-worker')
    Copy-Item -LiteralPath 'gtnh-worker/tools/README.md' -Destination (Join-Path $packageDir 'gtnh-worker/tools')
    $launcher = @'
@echo off
setlocal
set "JAVA_COMMAND=java"
if defined JAVA_HOME set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
if defined AMIDST_JAVA_HOME set "JAVA_COMMAND=%AMIDST_JAVA_HOME%\bin\java.exe"
"%JAVA_COMMAND%" -jar "%~dp0VIEWER_FILENAME" %*
set "VIEWER_EXIT_CODE=%ERRORLEVEL%"
exit /b %VIEWER_EXIT_CODE%
'@
    $launcher = ($launcher.Replace('VIEWER_FILENAME', $viewerName) -replace '\r?\n', "`r`n") + "`r`n"
    [IO.File]::WriteAllText((Join-Path $packageDir 'run-viewer.bat'), $launcher, [Text.Encoding]::ASCII)
    @"
Amidst-GTNH $packageTag

安装：关闭 GTNH，移除 mods 中旧 Worker，放入 $workerName 后启动游戏。
Viewer 在游戏外运行，不要将 $viewerName 放入 mods。
使用 Java 17 或更新版本双击 run-viewer.bat，或运行：
java -jar $viewerName

通信协议 $protocol；本版须同时更新 Viewer 与 Worker，并重启游戏。
更换 Worker 本身需要重启游戏，mods 中仅保留一个 Worker。
设置 > 语言：选择简体中文或 English。

发布说明、兼容性与已知限制：$ReleaseNotes。
使用和排障：docs/usage.md。开发维护：CONTRIBUTING.md。构建：BUILDING.md。
详细算法依据及旧版本资料由 docs/README.md 统一导航。
地图上的预测是候选，出生点估算尚不能保证与实际新建存档一致。
本次打包未进行完整游戏实测；验证记录见 build-info.json。
对应源码包：Amidst-GTNH-$packageTag-source.zip。
"@ | Set-Content -LiteralPath (Join-Path $packageDir 'README.txt') -Encoding utf8

    $sources = @(git -c core.quotepath=false ls-files --cached --others --exclude-standard | Sort-Object -Unique | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($LASTEXITCODE -ne 0 -or $sources.Count -eq 0) { throw 'Cannot enumerate release source' }
    $sourceSums = foreach ($file in $sources) { "$((Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant())  $file" }
    $sourceSums | Set-Content -LiteralPath (Join-Path $packageDir 'source-sha256.txt') -Encoding utf8
    [ordered]@{version=$releaseVersion; tag=$tag; packageTag=$packageTag; protocol=$protocol; packagedAt=(Get-Date).ToString('o'); baseCommit=(git rev-parse HEAD)
        source='Current working tree, including uncommitted changes; included in source ZIP'; tests=@($testResults)
        validation=[ordered]@{workerMappings='SRG verified by assembleRelease'; inGame='Not performed during release packaging'}
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $packageDir 'build-info.json') -Encoding utf8
    function Get-RelativeFiles($directory) {
        @(Get-ChildItem -LiteralPath $directory -File -Recurse |
            ForEach-Object { [IO.Path]::GetRelativePath($directory, $_.FullName).Replace('\','/') } |
            Sort-Object)
    }
    $sums = foreach ($file in Get-RelativeFiles $packageDir) { "$((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $packageDir $file)).Hash.ToLowerInvariant())  $file" }
    $sums | Set-Content -LiteralPath (Join-Path $packageDir 'SHA256SUMS.txt') -Encoding utf8

    function Write-VerifiedZip($path, $baseDir, $files, $prefix) {
        $archive = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($file in $files) {
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $baseDir $file), $prefix + $file.Replace('\','/'), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
            }
        } finally { $archive.Dispose() }
        $archive = [IO.Compression.ZipFile]::OpenRead($path)
        try {
            if ($archive.Entries.Count -ne $files.Count) { throw 'ZIP entry count mismatch' }
            foreach ($file in $files) {
                $stream = $archive.GetEntry($prefix + $file.Replace('\','/')).Open()
                try { $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
                finally { $stream.Dispose() }
                if ($digest -ne (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $baseDir $file)).Hash) { throw "ZIP mismatch: $file" }
            }
        } finally { $archive.Dispose() }
    }
    Write-VerifiedZip $zipPath $packageDir @(Get-RelativeFiles $packageDir) ''
    Write-VerifiedZip $sourceZipPath $repo $sources "Amidst-GTNH-$packageTag/"
    $assetSums = foreach ($path in @($zipPath, $sourceZipPath, $viewerPath, $workerPath)) { "$((Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($path))" }
    $assetSums | Set-Content -LiteralPath $assetSumsPath -Encoding utf8
    [ordered]@{zip=$zipPath; sourceZip=$sourceZipPath; sourceFiles=$sources.Count; tests=@($testResults)} | ConvertTo-Json -Depth 6
} finally { Pop-Location }
