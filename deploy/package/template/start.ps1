# QD助手 启动脚本（单实例）。
# 用法：  .\start.ps1              检查前置条件并启动（前台运行，Ctrl+C 停止）
#         .\start.ps1 -CheckOnly   只检查前置条件，不启动
# 密钥只来自环境变量（QD_ 前缀，进程 / 用户 / 系统级均可），不读取、不保存任何密码文件。
param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Fail([string]$msg) { Write-Host "[QD] $msg" -ForegroundColor Red; exit 1 }

# 1) Java 21+
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Fail '未找到 java，请安装 JDK / JRE 21 或更高版本。' }
$verLine = (cmd /c 'java -version 2>&1' | Select-Object -First 1)
if ($verLine -notmatch '"(\d+)') { Fail "无法识别 Java 版本：$verLine" }
if ([int]$matches[1] -lt 21) { Fail "Java 版本过低（$verLine），需要 21 或更高。" }

# 2) 程序与配置
$jar = Join-Path $PSScriptRoot 'qd-assistant.jar'
if (-not (Test-Path $jar)) { Fail "未找到 $jar" }
if (-not (Test-Path (Join-Path $PSScriptRoot 'config\application.yml'))) {
    Fail '未找到 config\application.yml。请复制 config\application.yml.example 为 application.yml 并按实际填写。'
}

# 3) 环境变量（进程 → 用户 → 系统）
$missing = @()
foreach ($n in 'QD_FEISHU_APP_SECRET', 'QD_JIRA_USERNAME', 'QD_JIRA_PASSWORD') {
    $v = $null
    foreach ($scope in 'Process', 'User', 'Machine') {
        $v = [Environment]::GetEnvironmentVariable($n, $scope)
        if ($v) { break }
    }
    if ($v) { Set-Item -Path "env:$n" -Value $v } else { $missing += $n }
}
if ($missing.Count -gt 0) { Fail ('缺少环境变量：' + ($missing -join '、') + '（见 README 第 3 步）') }

# 4) 单实例：同一时刻只能有一个 QD助手在运行（否则会重复同步、重复提醒）
$others = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*qd-assistant*.jar*' -and $_.ProcessId -ne $PID })
if ($others.Count -gt 0) {
    $ids = ($others | ForEach-Object { $_.ProcessId }) -join ', '
    if ($CheckOnly) { Write-Host "[QD] 注意：已有 QD助手 实例在运行（PID $ids），启动前请先停止。" -ForegroundColor Yellow }
    else { Fail "已有 QD助手 实例在运行（PID $ids）。请先停止后再启动，避免重复同步和重复提醒。" }
}

if ($CheckOnly) { Write-Host '[QD] 前置条件检查通过。' -ForegroundColor Green; exit 0 }

Write-Host '[QD] 启动中……（日志见 logs\qd-assistant.log）' -ForegroundColor Green
& java -Dfile.encoding=UTF-8 -jar $jar
exit $LASTEXITCODE
