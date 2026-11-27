# 停止并删除计划任务，同时结束正在运行的 QD助手 进程（不删除 data / logs / config）。
param([string]$TaskName = 'QD-Assistant')
$ErrorActionPreference = 'Continue'
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "[QD] 已删除计划任务 $TaskName"
}
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*qd-assistant*.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "[QD] 已结束进程 PID $($_.ProcessId)" }
