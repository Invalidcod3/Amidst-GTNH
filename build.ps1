[CmdletBinding()]
param(
    [switch]$Offline,
    [switch]$SkipTests,
    [string]$LocalNbtJar
)

$ErrorActionPreference = "Stop"
$gradle = Join-Path $PSScriptRoot "gradlew.bat"
$arguments = @("assembleRelease")

if ($Offline) {
    $arguments += "--offline"
}
if ($SkipTests) {
    $arguments += @("-x", "test")
}
if ($LocalNbtJar) {
    $resolvedNbtJar = (Resolve-Path -LiteralPath $LocalNbtJar).Path
    $arguments += "-PlocalNbtJar=$resolvedNbtJar"
}

& $gradle @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Build completed. Artifacts:"
Get-ChildItem (Join-Path $PSScriptRoot "build\release") -Filter *.jar |
    ForEach-Object { Write-Host "  $($_.FullName)" }
