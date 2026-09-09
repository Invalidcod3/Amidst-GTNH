#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    $metadata = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo 'src/main/resources/amidst/metadata.properties')))
    $viewerName = $metadata['amidst.build.filename'] + '.jar'
    if ($metadata['amidst.build.filename'] -notmatch '-(v\d+(?:\.\d+)*)$') { throw 'Missing release suffix' }
    $tag = $Matches[1]
    $workerName = "amidst-gtnh-worker-$tag.jar"
    $releaseVersion = $metadata['amidst.release.version']
    $packageDir = Join-Path $repo "build/Amidst-GTNH-$tag"
    $zipPath = "$packageDir.zip"
    $sourceZipPath = "$packageDir-source.zip"
    foreach ($path in @($packageDir, $zipPath, $sourceZipPath)) {
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

    New-Item -ItemType Directory -Path $packageDir | Out-Null
    Copy-Item -LiteralPath $viewerPath,$workerPath -Destination $packageDir
    foreach ($file in @('LICENSE', "docs/release-$tag.md", 'src/main/resources/licenses/VisualProspecting-LICENSE.txt', 'src/main/resources/licenses/VisualProspecting-NOTICE.txt')) {
        Copy-Item -LiteralPath $file -Destination $packageDir
    }
    $launcher = @'
@echo off
setlocal
set "JAVA_COMMAND=java"
if defined JAVA_HOME set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
if defined AMIDST_JAVA_HOME set "JAVA_COMMAND=%AMIDST_JAVA_HOME%\bin\java.exe"
"%JAVA_COMMAND%" -jar "%~dp0VIEWER_FILENAME" %*
set "VIEWER_EXIT_CODE=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %VIEWER_EXIT_CODE%
'@
    $launcher = ($launcher.Replace('VIEWER_FILENAME', $viewerName) -replace '\r?\n', "`r`n") + "`r`n"
    [IO.File]::WriteAllText((Join-Path $packageDir 'run-viewer.bat'), $launcher, [Text.Encoding]::ASCII)
    @"
Amidst-GTNH $tag

安装：关闭 GTNH，移除 mods 中旧 Worker，放入 $workerName 后启动游戏。
Viewer 在游戏外运行，不要将 $viewerName 放入 mods。
使用 Java 17 或更新版本双击 run-viewer.bat，或运行：
java -jar $viewerName

协议 21；已使用 v42/v43 Worker 时可只更新 Viewer，无需重启游戏。
更换 Worker 本身需要重启游戏，mods 中仅保留一个 Worker。
设置 > 语言：选择简体中文或 English。
图层 > 维度下方：切换结构、矿脉、流体，并使用下拉筛选。
世界 > 导出坐标：选择矿脉或流体后配置类型、产出、高度及数量等条件。

未记录矿脉是种子候选；L/Op 为每次操作产出，不是总储量。
部分新增维度只有探矿图层，尚无已验证的群系底图。
更新内容、兼容性与精度说明见 release-$tag.md。
本次打包未进行完整游戏实测；验证记录见 build-info.json。
对应源码包：Amidst-GTNH-$tag-source.zip。
"@ | Set-Content -LiteralPath (Join-Path $packageDir 'README.txt') -Encoding utf8

    $sources = @(git -c core.quotepath=false ls-files --cached --others --exclude-standard | Sort-Object -Unique | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($LASTEXITCODE -ne 0 -or $sources.Count -eq 0) { throw 'Cannot enumerate release source' }
    $sourceSums = foreach ($file in $sources) { "$((Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant())  $file" }
    $sourceSums | Set-Content -LiteralPath (Join-Path $packageDir 'source-sha256.txt') -Encoding utf8
    [ordered]@{version=$releaseVersion; tag=$tag; protocol=21; packagedAt=(Get-Date).ToString('o'); baseCommit=(git rev-parse HEAD)
        source='Current working tree, including uncommitted changes; included in source ZIP'; tests=@($testResults)
        validation=[ordered]@{workerMappings='SRG verified by assembleRelease'; inGame='Not performed during release packaging'}
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $packageDir 'build-info.json') -Encoding utf8
    $sums = foreach ($file in Get-ChildItem -LiteralPath $packageDir -File | Sort-Object Name) { "$((Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant())  $($file.Name)" }
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
    Write-VerifiedZip $zipPath $packageDir @(Get-ChildItem -LiteralPath $packageDir -File | Select-Object -ExpandProperty Name) ''
    Write-VerifiedZip $sourceZipPath $repo $sources "Amidst-GTNH-$tag/"
    $assetSums = foreach ($path in @($zipPath, $sourceZipPath, $viewerPath, $workerPath)) { "$((Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($path))" }
    $assetSums | Set-Content -LiteralPath (Join-Path $repo "build/Amidst-GTNH-$tag-SHA256SUMS.txt") -Encoding utf8
    [ordered]@{zip=$zipPath; sourceZip=$sourceZipPath; sourceFiles=$sources.Count; tests=@($testResults)} | ConvertTo-Json -Depth 6
} finally { Pop-Location }
