# QD助手 部署包

把 Jira 未完成任务同步到飞书多维表格，工作日 17:00 私聊提醒经办人，支持飞书里"立即同步"。
**同一时刻只能运行一个实例**（否则会重复同步、重复提醒）。运行环境：Windows，需要 Java 21 或更高版本；不需要数据库，不需要公网地址（飞书长连接，能访问 Jira 内网与飞书开放平台即可）。

## 目录

| 路径 | 说明 |
|---|---|
| `qd-assistant.jar` | 程序（自包含） |
| `start.ps1` | 启动脚本（含前置条件检查，`-CheckOnly` 只检查） |
| `install-task.ps1` / `uninstall-task.ps1` | 注册 / 删除"登录后自动启动"的计划任务 |
| `config/application.yml.example` | 配置模板，复制为 `config/application.yml` 后填写 |
| `data/` | 运行时生成：`reminder-state.json`（当日已发提醒，防重复，**升级时保留**） |
| `logs/` | 运行时生成：运行日志，保留 7 天，不含密码与令牌 |

## 部署步骤

1. **安装 Java 21+**：在 PowerShell 执行 `java -version` 确认。
2. **填写配置**：复制 `config\application.yml.example` 为 `config\application.yml`，填入表格 `app_token`、各 `table_id`、两个视图链接、运维 / 备份负责人 `open_id`。**缺少任何必填项，程序会拒绝启动并列出缺项**。
3. **设置环境变量**（密钥只放环境变量，不要写进配置文件或提交到仓库）。把尖括号里的内容换成真实值，在 PowerShell 逐条执行，然后**重新打开 PowerShell**：

   ```powershell
   [Environment]::SetEnvironmentVariable('QD_FEISHU_APP_SECRET', '<飞书应用 App Secret>', 'User')
   [Environment]::SetEnvironmentVariable('QD_JIRA_USERNAME', '<Jira 登录名>', 'User')
   [Environment]::SetEnvironmentVariable('QD_JIRA_PASSWORD', '<Jira 密码>', 'User')
   ```

4. **电源计划设为不休眠**（休眠期间会错过定时点；程序每 5 分钟检查补执行，但关机 / 断网期间仍会错过）：

   ```powershell
   powercfg /change standby-timeout-ac 0
   powercfg /change hibernate-timeout-ac 0
   ```

5. **检查**：`.\start.ps1 -CheckOnly`，应显示"前置条件检查通过"。
6. **试运行**：`.\start.ps1`，看到"QD助手已启动"后，在飞书测试群发 `#QD立即同步`，确认能同步；`Ctrl+C` 停止。
7. **开机 / 登录自启**：`.\install-task.ps1`（用当前 Windows 用户登录时启动，失败自动重启）。需要先停掉试运行的实例。

## 日常运维

- **看日志**：`Get-Content logs\qd-assistant.log -Tail 50`
- **升级**：`.\uninstall-task.ps1` → 用新的 `qd-assistant.jar` 覆盖旧文件（**保留 `config\` 与 `data\`**）→ `.\install-task.ps1`。
- **停止**：`.\uninstall-task.ps1`
- **失败通知**：同步 / 提醒失败时，运维与备份负责人会收到飞书私聊；配置错误启动失败时也会（飞书配置可用的前提下）。

## 注意

- 提醒对象、例外对应表、排除名单、节假日日历都在飞书多维表格里维护，不需要改本机配置。
- 工作流"立即同步"按钮必须由长期在职的账号、以**用户身份**发送消息，否则 QD助手 收不到。
- Jira 账号连续密码错误可能触发验证码锁定；请只用环境变量里的账号，修改密码后同步更新环境变量。
