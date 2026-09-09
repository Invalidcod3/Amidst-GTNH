param(
    [Parameter(Mandatory=$true)][int]$X,
    [Parameter(Mandatory=$true)][int]$Z,
    [int]$Width=64,
    [int]$Height=64,
    [int]$Step=1,
    [int]$Port=47117,
    [string]$Token='',
    [string]$OutputPath='biome-comparison.json'
)
$ErrorActionPreference='Stop'
function Invoke-Worker([hashtable]$Request) {
    $Request.protocol=21
    $Request.token=$Token
    $socket=[Net.Sockets.TcpClient]::new('127.0.0.1',$Port)
    try {
        $socket.ReceiveTimeout=35000
        $socket.SendTimeout=5000
        $stream=$socket.GetStream()
        $writer=[IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false),1024,$true)
        $writer.WriteLine(($Request | ConvertTo-Json -Compress))
        $writer.Flush()
        $reader=[IO.StreamReader]::new($stream)
        $response=$reader.ReadLine() | ConvertFrom-Json
        if (-not $response.ok) { throw $response.error }
        return $response
    } finally { $socket.Dispose() }
}
$info=Invoke-Worker @{command='hello'}
$response=Invoke-Worker @{command='compare_biomes'; seed=$info.seed; dimension=0; x=$X; z=$Z; width=$Width; height=$Height; step=$Step}
if ($response.biomeSource -ne 'loaded-chunk-comparison') { throw 'This check requires v33 or newer Worker.' }
$names=@{}
foreach ($biome in $info.biomes) { $names[[int]$biome.id]=$biome.name }
$compared=0
$coverage=@{}
$confusion=@{}
$riverChanges=0
$surfaceChanges=0
$mismatches=@(for ($row=0; $row -lt $Height; $row++) {
    for ($column=0; $column -lt $Width; $column++) {
        $index=$row*$Width+$column
        $actual=[int]$response.actualIds[$index]
        $predicted=[int]$response.ids[$index]
        if ($actual -lt 0) { continue }
        $compared++
        if (-not $coverage.ContainsKey($actual)) {
            $coverage[$actual]=[ordered]@{actualId=$actual; actual=$names[$actual]; points=0; mismatches=0}
        }
        $coverage[$actual].points++
        $pair="$actual/$predicted"
        if (-not $confusion.ContainsKey($pair)) {
            $confusion[$pair]=[ordered]@{actualId=$actual; predictedId=$predicted; points=0}
        }
        $confusion[$pair].points++
        $stages=$null
        if ($response.predictions) {
            $stages=$response.predictions[$index]
            if ($stages.baseId -ne $stages.riverId) { $riverChanges++ }
            if ($stages.riverId -ne $predicted) { $surfaceChanges++ }
        }
        if ($actual -ne $predicted) {
            $coverage[$actual].mismatches++
            [pscustomobject]@{x=$X+$column*$Step; z=$Z+$row*$Step; actualId=$actual; actual=$names[$actual]; predictedId=$predicted; predicted=$names[$predicted]; stages=$stages}
        }
    }
})
$report=[ordered]@{time=(Get-Date).ToString('o'); seed=$info.seed; x=$X; z=$Z; width=$Width; height=$Height; step=$Step; predictionPipeline=$response.predictionPipeline; comparedLoadedPoints=$compared; unloadedPoints=$Width*$Height-$compared; mismatchCount=$mismatches.Count; riverChangedPoints=$riverChanges; surfaceChangedPoints=$surfaceChanges; biomeCoverage=@($coverage.Values | Sort-Object { $_.actualId }); confusion=@($confusion.Values | Sort-Object { $_.actualId }, { $_.predictedId }); mismatches=$mismatches}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
[pscustomobject]@{seed=$info.seed; comparedLoadedPoints=$compared; mismatchCount=$mismatches.Count; report=[IO.Path]::GetFullPath($OutputPath)}
