# 构建部署包：mvn package → dist\qd-assistant-<版本>\（并压缩为 zip）。
# 用法（在仓库任意位置）：  powershell -ExecutionPolicy Bypass -File deploy\package\build-package.ps1 [-SkipTests]
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$backend = Join-Path $repo 'backend'
$template = Join-Path $PSScriptRoot 'template'

Push-Location $backend
try {
    $args = if ($SkipTests) { @('-q', '-DskipTests', 'package') } else { @('-q', 'package') }
    & mvn @args
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败（含测试），已停止。' }
} finally { Pop-Location }

$jar = Get-ChildItem (Join-Path $backend 'target') -Filter 'qd-assistant-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
if (-not $jar) { throw '未找到构建出的 jar。' }
$version = ($jar.BaseName -replace '^qd-assistant-', '')

$out = Join-Path $repo "dist\qd-assistant-$version"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $out 'config') -Force | Out-Null

Copy-Item $jar.FullName (Join-Path $out 'qd-assistant.jar')
Copy-Item (Join-Path $template '*') $out -Recurse -Exclude 'config'
Copy-Item (Join-Path $repo 'deploy\local\config\application.yml.example') (Join-Path $out 'config\application.yml.example')
Set-Content -Path (Join-Path $out 'VERSION.txt') -Value "qd-assistant $version`r`n构建时间 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8

$zip = "$out.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $out -DestinationPath $zip
Write-Host "已生成：$out"
Write-Host "已压缩：$zip"
