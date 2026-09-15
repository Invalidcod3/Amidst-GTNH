#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$FormatterJar,
    [string]$Java = 'java',
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path $PSScriptRoot -Parent
$formatter = (Resolve-Path -LiteralPath $FormatterJar).Path
# google-java-format 1.28.0, all-deps JAR from Maven Central.
$expectedHash = '32342e7c1b4600f80df3471da46aee8012d3e1445d5ea1be1fb71289b07cc735'
if ((Get-FileHash -LiteralPath $formatter -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'Expected google-java-format 1.28.0 all-deps JAR; SHA-256 mismatch'
}
$sources = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'java-format-files.txt') |
    Where-Object { $_ -and -not $_.StartsWith('#') } |
    ForEach-Object { Join-Path $repo $_ })
$arguments = @('-jar', $formatter, '--aosp')
if ($Check) { $arguments += @('--dry-run', '--set-exit-if-changed') }
else { $arguments += '--replace' }
& $Java @arguments @sources
if ($LASTEXITCODE -ne 0) { throw "Java formatting failed (exit $LASTEXITCODE)" }
