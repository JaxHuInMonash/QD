# 注册"登录后自动启动"的计划任务，并立即启动一次。
# 用法：  .\install-task.ps1                    （当前 Windows 用户登录时启动；失败自动重启，最多 5 次，间隔 1 分钟）
# 前置：start.ps1 -CheckOnly 必须通过；同一台机器上不能再有别的 QD助手 在运行。
param([string]$TaskName = 'QD-Assistant')
$ErrorActionPreference = 'Stop'
$start = Join-Path $PSScriptRoot 'start.ps1'

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $start -CheckOnly
if ($LASTEXITCODE -ne 0) { throw '前置条件检查未通过，已取消注册。' }

$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$start`"" -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
    -Description 'QD助手：Jira 未完成任务同步与 17:00 提醒' -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Host "[QD] 已注册并启动计划任务 $TaskName（用户 $user，登录时自动启动）。" -ForegroundColor Green
Write-Host '[QD] 提示：请把电源计划设为"从不休眠"，否则休眠期间会错过定时点（见 README 第 4 步）。'
