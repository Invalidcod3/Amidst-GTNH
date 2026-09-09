param(
    [Parameter(Mandatory=$true)][int]$TargetProcessId,
    [Parameter(Mandatory=$true)][string]$JdkBin,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [ValidateRange(5,300)][int]$Seconds=45
)
$ErrorActionPreference='Stop'
$directory=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$name='GTNH_profile_'+[DateTime]::Now.ToString('yyyyMMdd_HHmmss')+'_'+$TargetProcessId
$jfc=Join-Path $directory ($name+'.jfc')
$jfr=Join-Path $directory ($name+'.jfr')
$profile=Join-Path (Split-Path -Parent $JdkBin) 'lib/jfr/profile.jfc'
& (Join-Path $JdkBin 'jfr.exe') configure --input $profile --output $jfc 'jdk.ExecutionSample#period=10ms' 'jdk.ThreadPark#enabled=true' 'jdk.ThreadPark#threshold=10ms' 'jdk.JavaMonitorEnter#threshold=10ms' 'jdk.SocketRead#threshold=10ms' 'jdk.SocketWrite#threshold=10ms'
if ($LASTEXITCODE -ne 0) { throw 'Unable to create JFR configuration.' }
& (Join-Path $JdkBin 'jcmd.exe') $TargetProcessId JFR.start "name=$name" "settings=$jfc" "duration=${Seconds}s" "filename=$jfr"
if ($LASTEXITCODE -ne 0) { throw 'Unable to attach JFR. Check process ownership and JDK compatibility.' }
Write-Output "Recording started. Reproduce the slow operation now. The recording saves automatically after $Seconds seconds:"
Write-Output $jfr
