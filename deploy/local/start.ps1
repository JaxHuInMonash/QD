# 一期：在办公电脑上启动 QD助手（单实例）。
# 前置：已设置用户级环境变量 QD_FEISHU_APP_SECRET、QD_JIRA_USERNAME、QD_JIRA_PASSWORD；已准备 config\application.yml。
# 开机自启：用"任务计划程序"新建登录时触发的任务，运行 powershell -NoProfile -File <本脚本完整路径>。
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$jar = Join-Path $PSScriptRoot '..\..\backend\target\qd-assistant-0.1.0.jar'
if (-not (Test-Path $jar)) { throw "未找到 $jar，请先在 backend 目录执行 mvn -DskipTests package" }
# 阻止系统休眠由电源设置负责（见 README）；此处只负责启动
& java -jar $jar
