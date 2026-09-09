param(
    [int]$X=32000,
    [int]$Z=32000,
    [ValidateRange(1,16)][int]$Tiles=4,
    [int]$Port=47117,
    [string]$DimensionKey='minecraft:overworld',
    [string]$Token='',
    [string]$OutputPath='worker-tile-profile.json'
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
$dimension = switch ($DimensionKey) {
    'minecraft:overworld' { 0 }
    'minecraft:the_nether' { -1 }
    'minecraft:the_end' { 1 }
    'bartworks:ross128b' { $info.ross128bDimensionId }
    'extrautilities:deep_dark' { $info.deepDarkDimensionId }
    'twilightforest:twilight_forest' { $info.twilightForestDimensionId }
    default { throw "Unsupported profiling dimension key: $DimensionKey" }
}
if ($null -eq $dimension) { throw 'Worker did not advertise the requested dimension ID' }
$results=@(for ($index=0; $index -lt $Tiles; $index++) {
    $tileX=$X+$index*512
    $response=Invoke-Worker @{command='biomes'; seed=$info.seed; dimension=$dimension; dimensionKey=$DimensionKey; x=$tileX; z=$Z; width=128; height=128; step=4; profile=$true}
    [pscustomobject]@{x=$tileX; z=$Z; samples=$response.ids.Count; computeMillis=$response.computeMillis;
        elapsedMillis=$response.elapsedMillis; queueMillis=$response.queueMillis; maxSliceMillis=$response.maxSliceMillis;
        slices=$response.slices; predictionStats=$response.predictionStats}
})
[ordered]@{time=(Get-Date).ToString('o'); seed=$info.seed; protocol=$info.protocol; dimension=$dimension; dimensionKey=$DimensionKey; tiles=$results} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
$results | Format-Table -AutoSize
Write-Output ([IO.Path]::GetFullPath($OutputPath))
