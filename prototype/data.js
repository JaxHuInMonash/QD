/*
 * 原型模拟数据（全部为虚构示例，非真实 Jira / 飞书数据）
 * - 邮箱统一使用 example.com，避免与真实账号混淆
 * - 节假日日历为示例，正式使用需按国务院公布的安排录入
 * - Jira 类型名与状态名节选自 2026-11-26 实测的 VERA2506M 项目（见 docs/design/03.数据格式设计.md 4.4）
 */
window.MOCK = {
  // 飞书通讯录（提醒的接收方）
  feishuUsers: [
    { id: 'fs_hhk', name: '胡桓康', email: 'huhk@example.com', status: 'active', roles: ['研发', '运维负责人', '表格管理员'] },
    { id: 'fs_zz',  name: '朱卓',   email: 'zhuzhuo@example.com', status: 'active', roles: ['项目负责人', '主表格所有者', '备份负责人'] },
    { id: 'fs_lm',  name: '李明',   email: 'liming@example.com', status: 'active', roles: ['研发'] },
    { id: 'fs_wf',  name: '王芳',   email: 'wangfang@example.com', status: 'active', roles: ['测试'] },
    { id: 'fs_cc',  name: '陈晨',   email: 'chenchen@example.com', status: 'active', roles: ['研发（Jira 邮箱与飞书不一致）'] },
    { id: 'fs_zl',  name: '赵磊',   email: 'zhaolei@example.com', status: 'active', roles: ['外部团队（在排除名单中）'] }
  ],

  // Jira 账号（经办人 / 报告人）
  jiraUsers: [
    { acc: 'huhk',     name: '胡桓康', email: 'huhk@example.com' },
    { acc: 'zhuzhuo',  name: '朱卓',   email: 'zhuzhuo@example.com' },
    { acc: 'liming',   name: '李明',   email: 'liming@example.com' },
    { acc: 'wangfang', name: '王芳',   email: 'wangfang@example.com' },
    { acc: 'chenchen', name: '陈晨',   email: 'chenchen.partner@example.org' }, // 邮箱对不上 → 需例外对应
    { acc: 'zhaolei',  name: '赵磊',   email: 'zhaolei@example.com' },
    { acc: 'sunwei',   name: '孙伟',   email: 'sunwei@example.com' }           // 已离职，飞书无账号
  ],

  // Jira 类型 → 表格类型（03 Q4）与所用工作流；table 为 null 表示不进入主表格
  jiraTypes: [
    { name: 'Story',           table: 'Story', wf: 'story' },
    { name: '任务',            table: 'Task',  wf: 'task' },
    { name: 'Bug（软件测试）', table: 'Bug',   wf: 'bug' },
    { name: 'BUG（实验室）',   table: 'Bug',   wf: 'bug' },
    { name: 'BUG（硬件测试）', table: 'Bug',   wf: 'bug' },
    { name: 'Fix',             table: null,    wf: 'fix' },
    { name: '子任务',          table: null,    wf: 'task' }
  ],

  // 工作流状态 → 状态类别（节选自实测数据；归类由 Jira 决定）
  workflows: {
    story: [
      { name: 'backlog', category: 'todo' }, { name: '挂起', category: 'todo' },
      { name: 'Story开发中', category: 'inprogress' }, { name: 'Story联调中', category: 'inprogress' }, { name: '测试验收Story中', category: 'inprogress' },
      { name: '完成', category: 'done' }
    ],
    task: [
      { name: '待办', category: 'todo' }, { name: '挂起', category: 'todo' },
      { name: '开发中', category: 'inprogress' }, { name: '代码审核中', category: 'inprogress' },
      { name: '完成', category: 'done' }
    ],
    bug: [
      { name: '开放', category: 'todo' }, { name: '重新打开', category: 'todo' }, { name: 'Not an issue', category: 'todo' },
      { name: '处理中', category: 'inprogress' }, { name: 'duplicate', category: 'inprogress' }, { name: '联调测试', category: 'inprogress' },
      { name: '已解决', category: 'done' }, { name: 'close', category: 'done' }, { name: '无法解决', category: 'done' }
    ],
    fix: [
      { name: '待提交', category: 'todo' }, { name: '开发中', category: 'inprogress' }, { name: '回归测试中', category: 'inprogress' },
      { name: '完成', category: 'done' }, { name: '问题挂起中', category: 'done' }
    ]
  },

  projects: ['VERA2506M', 'VERA2507A'],
  priorities: ['最高', '高', '中', '低'],

  // Jira Issue（jiraType 为 Jira 类型名；assignee 为 null 表示未分配；inBoard=false 表示不在看板 22909 范围内）
  issues: [
    { key: 'VERA2506M-251', title: '人形机器人 APP 首页改版', jiraType: 'Story', project: 'VERA2506M', assignee: 'huhk', reporter: 'zhuzhuo', status: 'Story开发中', priority: '高', created: '2026-10-08 10:12', updated: '2026-11-24 18:30', inBoard: true },
    { key: 'VERA2506M-255', title: '设备绑定流程优化', jiraType: 'Story', project: 'VERA2506M', assignee: 'huhk', reporter: 'zhuzhuo', status: 'backlog', priority: '中', created: '2026-10-15 09:40', updated: '2026-11-20 11:02', inBoard: true },
    { key: 'VERA2506M-262', title: '语音指令设置页', jiraType: 'Story', project: 'VERA2506M', assignee: 'huhk', reporter: 'zhuzhuo', status: 'Story联调中', priority: '中', created: '2026-10-21 14:05', updated: '2026-11-23 16:44', inBoard: true },
    { key: 'VERA2506M-270', title: '固件升级进度展示', jiraType: 'Story', project: 'VERA2506M', assignee: 'huhk', reporter: 'zhuzhuo', status: '挂起', priority: '低', created: '2026-11-02 10:30', updated: '2026-11-02 10:30', inBoard: true },
    { key: 'VERA2506M-281', title: '多语言文案接入', jiraType: 'Story', project: 'VERA2506M', assignee: 'huhk', reporter: 'zhuzhuo', status: 'backlog', priority: '低', created: '2026-11-10 15:20', updated: '2026-11-10 15:20', inBoard: true },
    { key: 'VERA2506M-301', title: '埋点 SDK 升级', jiraType: '任务', project: 'VERA2506M', assignee: 'huhk', reporter: 'liming', status: '开发中', priority: '中', created: '2026-11-04 09:00', updated: '2026-11-24 10:15', inBoard: true },
    { key: 'VERA2506M-315', title: '整理接口文档', jiraType: '任务', project: 'VERA2506M', assignee: 'huhk', reporter: 'zhuzhuo', status: '待办', priority: '低', created: '2026-11-12 17:10', updated: '2026-11-12 17:10', inBoard: true },
    { key: 'VERA2506M-322', title: '旧版设置页下线', jiraType: '任务', project: 'VERA2506M', assignee: 'huhk', reporter: 'zhuzhuo', status: '完成', priority: '低', created: '2026-11-01 11:00', updated: '2026-11-18 09:30', inBoard: true },
    { key: 'VERA2506M-325', title: '首页卡片子任务：图标替换', jiraType: '子任务', project: 'VERA2506M', assignee: 'huhk', reporter: 'huhk', status: '开发中', priority: '低', created: '2026-11-05 09:00', updated: '2026-11-20 09:00', inBoard: true },

    { key: 'VERA2506M-288', title: '机器人状态卡片动效', jiraType: 'Story', project: 'VERA2506M', assignee: 'liming', reporter: 'zhuzhuo', status: 'Story开发中', priority: '高', created: '2026-11-05 10:00', updated: '2026-11-24 19:12', inBoard: true },
    { key: 'VERA2506M-330', title: '首页卡片点击无响应', jiraType: 'Bug（软件测试）', project: 'VERA2506M', assignee: 'liming', reporter: 'wangfang', status: '重新打开', priority: '最高', created: '2026-11-14 13:22', updated: '2026-11-24 15:40', inBoard: true },
    { key: 'VERA2506M-334', title: '横屏时底部导航遮挡', jiraType: 'Bug（软件测试）', project: 'VERA2506M', assignee: 'liming', reporter: 'wangfang', status: 'duplicate', priority: '中', created: '2026-11-18 16:05', updated: '2026-11-18 16:05', inBoard: true },
    { key: 'VERA2506M-339', title: '深色模式下文字看不清', jiraType: 'Bug（软件测试）', project: 'VERA2506M', assignee: 'liming', reporter: 'wangfang', status: '已解决', priority: '中', created: '2026-11-19 10:41', updated: '2026-11-24 17:20', inBoard: true },
    { key: 'VERA2506M-342', title: '充电指示灯异常闪烁', jiraType: 'BUG（硬件测试）', project: 'VERA2506M', assignee: 'liming', reporter: 'wangfang', status: '处理中', priority: '高', created: '2026-11-20 11:30', updated: '2026-11-24 09:10', inBoard: true },
    { key: 'VERA2506M-345', title: '登录接口偶发超时（Fix 单）', jiraType: 'Fix', project: 'VERA2506M', assignee: 'liming', reporter: 'wangfang', status: '开发中', priority: '中', created: '2026-11-21 10:00', updated: '2026-11-24 10:00', inBoard: true },

    { key: 'VERA2507A-012', title: '回归测试用例整理', jiraType: '任务', project: 'VERA2507A', assignee: 'wangfang', reporter: 'zhuzhuo', status: '代码审核中', priority: '中', created: '2026-11-06 09:30', updated: '2026-11-24 11:00', inBoard: true },
    { key: 'VERA2507A-018', title: '配网失败率统计', jiraType: '任务', project: 'VERA2507A', assignee: 'wangfang', reporter: 'zhuzhuo', status: '待办', priority: '低', created: '2026-11-20 14:00', updated: '2026-11-20 14:00', inBoard: true },

    { key: 'VERA2507A-021', title: '蓝牙配网重试机制', jiraType: 'Story', project: 'VERA2507A', assignee: 'chenchen', reporter: 'zhuzhuo', status: 'Story开发中', priority: '高', created: '2026-11-03 10:20', updated: '2026-11-23 18:00', inBoard: true },
    { key: 'VERA2507A-025', title: '配网页面闪退', jiraType: 'BUG（实验室）', project: 'VERA2507A', assignee: 'chenchen', reporter: 'wangfang', status: '开放', priority: '最高', created: '2026-11-21 09:15', updated: '2026-11-21 09:15', inBoard: true },

    { key: 'VERA2507A-030', title: '第三方地图 SDK 评估', jiraType: '任务', project: 'VERA2507A', assignee: 'zhaolei', reporter: 'zhuzhuo', status: '开发中', priority: '中', created: '2026-11-11 10:00', updated: '2026-11-22 10:00', inBoard: true },
    { key: 'VERA2507A-033', title: '配网引导文案优化（尚未分配）', jiraType: 'Story', project: 'VERA2507A', assignee: null, reporter: 'zhuzhuo', status: 'backlog', priority: '中', created: '2026-11-23 15:00', updated: '2026-11-23 15:00', inBoard: true },

    { key: 'VERA2506M-240', title: '历史遗留：推送证书更新', jiraType: '任务', project: 'VERA2506M', assignee: 'sunwei', reporter: 'zhuzhuo', status: '待办', priority: '中', created: '2026-09-20 10:00', updated: '2026-10-30 10:00', inBoard: true },

    { key: 'VERA2506M-350', title: '版本发布检查清单', jiraType: '任务', project: 'VERA2506M', assignee: 'zhuzhuo', reporter: 'zhuzhuo', status: '待办', priority: '高', created: '2026-11-22 09:00', updated: '2026-11-22 09:00', inBoard: true },

    { key: 'OTHER-007', title: '其他团队的需求（不在看板）', jiraType: 'Story', project: 'OTHER', assignee: 'liming', reporter: 'zhaolei', status: 'backlog', priority: '中', created: '2026-11-01 09:00', updated: '2026-11-01 09:00', inBoard: false }
  ],

  // 管理表格初始配置
  mgmt: {
    exclude: [
      { acc: 'zhaolei', reason: '外部团队成员，不接收本项目提醒' }
    ],
    exceptions: [
      // 初始为空：用于演示"陈晨邮箱对不上 → 未送达 → 运维补充对应 → 次日送达"
    ],
    holidays: [
      { date: '2026-11-27', type: 'holiday', name: '示例：法定节假日（演示用）' },
      { date: '2026-11-29', type: 'makeup',  name: '示例：调休补班（演示用）' },
      { date: '2027-01-01', type: 'holiday', name: '元旦（示例）' }
    ]
  }
};
