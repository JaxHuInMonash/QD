# DQ助手（人形机器人/APP应用开发）：
    初步想法（by 朱卓）：
1.按照项目，人员，状态去查询Jira工作流状态，每日定时更新到多维表格。
2.新建多维表格，合理设计展示情况，支持定时更新，手动触发更新。
3.每日Jira状态，定时17:00点通知个人更新，同时发送个人名下所有story/task/bugr到多维表格机器人。
当前Jira看板：https://jira.dreame.tech/secure/Dashboard.jspa?selectPageId=22909
目标多维表格：敏捷项目管理（需要调整，删减）
参考多维表格：【收集表】软硬件功能配置 R5088A/R6001A/R500VH
story:https://jira.dreame.tech/browse/VERA2506M-251

## 目录

- `docs/`：需求文档与分阶段设计文档（01–06）
- `prototype/`：可交互原型（演示用）
- `backend/`：后端服务（Java 21 + Spring Boot 3，Maven）。构建：`cd backend && mvn package`；测试：`mvn test`
- `deploy/`：部署相关（一期办公电脑运行：`deploy/local/start.ps1`，配置样例见 `deploy/local/config/`、`deploy/.env.example`）
- `poc/`：技术验证脚本（非业务代码）
