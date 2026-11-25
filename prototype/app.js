/* QD助手 · Jira 未完成任务提醒 —— 可交互原型（纯前端，模拟数据） */
(function () {
  'use strict';

  const M = window.MOCK;
  const clone = (o) => JSON.parse(JSON.stringify(o));
  const $ = (sel, el) => (el || document).querySelector(sel);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  const OPS_ID = 'fs_hhk';      // 运维负责人
  const BACKUP_ID = 'fs_zz';    // 备份负责人
  const TABLE_ADMINS = ['fs_hhk', 'fs_zz']; // 表格管理员 / 负责人（可点“立即同步”）
  const MGMT_ACCESS = ['fs_hhk', 'fs_zz'];  // 管理表格可访问
  const SEND_FAIL_TARGET = 'fs_wf';         // 演示发送失败的对象：王芳
  const CATEGORY_LABEL = { todo: '待办', inprogress: '进行中', done: '完成' };
  const WEEK = ['日', '一', '二', '三', '四', '五', '六'];

  /* ---------------- 状态 ---------------- */
  const S = {
    role: OPS_ID,
    tab: 'table',
    view: 'mine',
    mgmtTab: 'exclude',
    date: '2026-11-25',
    failMode: 'none',          // 下次同步：none | retryOk | fail
    sendFail: 'none',          // 17:00 发送给王芳：none | retryOk | fail（演示 Q4 发送重试）
    jira: clone(M.issues),
    mgmt: clone(M.mgmt),
    table: [],                 // 主表格：仅未完成任务
    lastSync: null,            // 最近一次成功同步时间 'YYYY-MM-DD HH:mm'
    manualResult: null,        // 手动同步结果（F2.4）
    inbox: {},                 // feishuId -> [msg]
    sent: {},                  // 'date|feishuId' -> true（每人每天最多 1 条定时提醒）
    ran: {}                    // 'date|slot' -> true（演示用：当日已执行的定时任务）
  };

  /* ---------------- 工具 ---------------- */
  const statusCat = (name) => (M.statuses.find((s) => s.name === name) || {}).category || 'todo';
  const jiraUser = (acc) => M.jiraUsers.find((u) => u.acc === acc) || { acc, name: acc, email: '' };
  const fsUser = (id) => M.feishuUsers.find((u) => u.id === id);
  const fmtShort = (dt) => (dt ? dt.slice(5) : '—');
  // Q1：按飞书人员展示；对应不上的显示 Jira 姓名并标注“未对应”
  const personName = (acc) => { const f = jiraToFeishu(acc); return f ? f.name : jiraUser(acc).name; };
  const personHtml = (acc) => { const f = jiraToFeishu(acc); const n = f ? f.name : jiraUser(acc).name;
    return `<span class="avatar ${f ? '' : 'avatar-x'}">${esc(n.slice(-1))}</span>${esc(n)}${f ? '' : ' <span class="tag-unmapped" title="Jira 账号未能对应到飞书人员">未对应</span>'}`; };
  const dateLabel = (d) => {
    const dt = new Date(d + 'T00:00:00');
    return `${d}（周${WEEK[dt.getDay()]}）`;
  };

  const yearEntered = (y) => S.mgmt.holidays.some((x) => x.date.slice(0, 4) === String(y));
  function dayInfo(d) {
    const h = S.mgmt.holidays.find((x) => x.date === d);
    const wd = new Date(d + 'T00:00:00').getDay();
    const missing = !yearEntered(d.slice(0, 4)) ? '（该年日历未录入，按周一至周五判断）' : '';
    if (h && h.type === 'holiday') return { work: false, label: '法定节假日 · ' + h.name };
    if (h && h.type === 'makeup') return { work: true, label: '调休补班日 · ' + h.name };
    if (wd === 0 || wd === 6) return { work: false, label: '周末' + missing };
    return { work: true, label: '工作日' + missing };
  }

  // R3：默认按公司邮箱对应；对不上时查例外对应表
  function jiraToFeishu(acc) {
    const ju = jiraUser(acc);
    const byEmail = M.feishuUsers.find((u) => u.email && u.email === ju.email);
    if (byEmail) return byEmail;
    const ex = S.mgmt.exceptions.find((e) => e.acc === acc);
    if (ex) return fsUser(ex.fsId) || null;
    return null;
  }

  function toast(text, kind) {
    const box = $('#toasts');
    const el = document.createElement('div');
    el.className = 'toast ' + (kind || '');
    el.textContent = text;
    box.appendChild(el);
    setTimeout(() => el.classList.add('out'), 3200);
    setTimeout(() => el.remove(), 3700);
  }

  function pushMsg(fsId, msg) {
    (S.inbox[fsId] = S.inbox[fsId] || []).push(clone(msg));
  }
  // Q3：异常通知同时私聊运维负责人与备份负责人
  function pushAlert(msg) { pushMsg(OPS_ID, msg); pushMsg(BACKUP_ID, msg); }

  /* ---------------- 业务逻辑 ---------------- */

  // F1：同步（含 F5.1 重试 1 次）
  function doSync(trigger, time, silent) {
    const at = `${S.date} ${time}`;
    let attempts = S.failMode === 'fail' ? 2 : S.failMode === 'retryOk' ? 1 : 0; // 失败次数
    if (S.failMode !== 'none') S.failMode = 'none', syncFailSelect();
    const failed = attempts >= 2;

    if (failed) {
      pushAlert({
        kind: 'alert', at,
        title: '同步失败通知',
        body: `${trigger}（${at}）同步失败，已自动重试 1 次仍失败。\n原因：无法访问 Jira（模拟）。\n主表格保持上一次成功同步的数据（截至 ${fmtShort(S.lastSync)}）。\n由运维负责人胡桓康处理；其不在时由备份负责人朱卓接替。`
      });
      if (trigger === '手动同步') S.manualResult = { at, ok: false, count: 0 };
      if (!silent) toast(`${trigger}失败（已重试 1 次），已私聊通知运维负责人与备份负责人`, 'err');
      return false;
    }

    // R2：只保留看板范围内未完成任务；以 Issue 编号去重
    const map = new Map();
    S.jira.forEach((i) => {
      if (i.deleted || !i.inBoard || statusCat(i.status) === 'done') return;
      map.set(i.key, {
        key: i.key, title: i.title, type: i.type, project: i.project,
        assignee: i.assignee, reporter: i.reporter, status: i.status,
        category: statusCat(i.status), priority: i.priority,
        created: i.created, updated: i.updated, synced: at
      });
    });
    const before = new Set(S.table.map((r) => r.key));
    S.table = Array.from(map.values());
    const removed = Array.from(before).filter((k) => !map.has(k));
    S.lastSync = at;
    if (trigger === '手动同步') S.manualResult = { at, ok: true, count: S.table.length, retried: attempts === 1 };
    if (!silent) {
      let t = `${trigger}完成：${S.table.length} 条未完成任务`;
      if (removed.length) t += `，移除 ${removed.length} 条（已完成/移出看板/已删除）`;
      if (attempts === 1) t += '（首次失败，重试成功）';
      toast(t, 'ok');
    }
    return true;
  }

  // Q6/Q8：每年 12 月 1 日起，每周一 09:00 检查次年日历是否已录入
  function calendarCheck() {
    const dt = new Date(S.date + 'T00:00:00');
    if (dt.getMonth() !== 11 || dt.getDay() !== 1) return;
    const next = dt.getFullYear() + 1;
    if (yearEntered(next)) return;
    const k = `${S.date}|calendar`;
    if (S.ran[k]) return; S.ran[k] = true;
    pushAlert({ kind: 'alert', at: `${S.date} 09:00`, title: '请录入次年节假日日历',
      body: `管理表格中尚未录入 ${next} 年的法定节假日与调休补班安排。\n未录入期间将按“周一至周五为工作日”判断，可能导致节假日误发提醒、调休补班日漏发。\n请在管理表格「节假日日历」中录入；录入后不再提醒（每周一 09:00 检查）。` });
    toast(`年底日历检查：${next} 年日历未录入，已提醒运维负责人与备份负责人`, 'warn');
  }

  // F2.1：定时同步，仅法定工作日
  function scheduledSync(time) {
    const di = dayInfo(S.date);
    if (!di.work) { toast(`${dateLabel(S.date)} 是${di.label.split(' · ')[0]}，不执行 ${time} 定时同步`, 'warn'); return; }
    S.ran[`${S.date}|${time}`] = true;
    doSync(`${time} 定时同步`, time);
  }

  // F3：17:00 个人提醒
  function sendReminders() {
    const di = dayInfo(S.date);
    if (!di.work) { toast(`${dateLabel(S.date)} 是${di.label.split(' · ')[0]}，不发送提醒`, 'warn'); return; }
    S.ran[`${S.date}|17:00`] = true;
    const at = `${S.date} 17:00`;
    const excluded = new Set(S.mgmt.exclude.map((e) => e.acc));
    const byAcc = {};
    S.table.forEach((r) => { (byAcc[r.assignee] = byAcc[r.assignee] || []).push(r); });

    let sent = 0, skipped = 0, retried = 0;
    const undelivered = [];
    Object.keys(byAcc).forEach((acc) => {
      if (excluded.has(acc)) return;
      const ju = jiraUser(acc);
      const fu = jiraToFeishu(acc);
      if (!fu) { undelivered.push({ name: ju.name, acc, reason: `账号无法对应（Jira 邮箱 ${ju.email} 在飞书中不存在，且未配置例外对应）` }); return; }
      if (fu.status !== 'active') { undelivered.push({ name: ju.name, acc, reason: '飞书账号已停用 / 已离职' }); return; }
      const k = `${S.date}|${fu.id}`;
      if (S.sent[k]) { skipped++; return; }
      // Q4：发送出错自动重试 1 次，仍失败才计入未送达（演示：对王芳模拟发送出错）
      if (fu.id === SEND_FAIL_TARGET && S.sendFail !== 'none') {
        const mode = S.sendFail; S.sendFail = 'none'; syncSendFailSelect();
        if (mode === 'fail') { undelivered.push({ name: fu.name, acc, reason: '发送出错（已自动重试 1 次仍失败）' }); return; }
        retried++;
      }
      S.sent[k] = true;
      pushMsg(fu.id, { kind: 'remind', at, dataAt: S.lastSync, items: byAcc[acc].map((r) => r.key), snapshot: clone(byAcc[acc]) });
      sent++;
    });

    if (undelivered.length) {
      pushAlert({
        kind: 'alert', at, title: '提醒未送达汇总',
        body: `今日 17:00 提醒有 ${undelivered.length} 人未送达：\n` + undelivered.map((u) => `· ${u.name}（Jira：${u.acc}）：${u.reason}`).join('\n') + '\n可在管理表格的「例外对应表 / 排除名单」中处理。'
      });
    }
    let t = `17:00 提醒：已私聊 ${sent} 人`;
    if (undelivered.length) t += `，${undelivered.length} 人未送达（已汇总给运维负责人与备份负责人）`;
    if (skipped) t += `，${skipped} 人今日已提醒过`;
    if (retried) t += `（${retried} 人首次发送出错，重试成功）`;
    if (!sent && !undelivered.length && !skipped) t = '17:00 提醒：没有需要提醒的人';
    toast(t, undelivered.length ? 'warn' : 'ok');
  }

  /* ---------------- 渲染：顶部 ---------------- */
  function renderTop() {
    const sel = $('#role');
    sel.innerHTML = M.feishuUsers.map((u) => `<option value="${u.id}" ${u.id === S.role ? 'selected' : ''}>${esc(u.name)} · ${esc(u.roles.join(' / '))}</option>`).join('');
    $('#date').value = S.date;
    const di = dayInfo(S.date);
    $('#dayInfo').innerHTML = `<span class="pill ${di.work ? 'pill-ok' : 'pill-gray'}">${esc(di.label)}</span>`;
    ['09:00', '16:50', '17:00'].forEach((t) => {
      const b = document.querySelector(`[data-run="${t}"]`);
      b.classList.toggle('done', !!S.ran[`${S.date}|${t}`]);
    });
    document.querySelectorAll('.nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === S.tab));
    const unread = (S.inbox[S.role] || []).filter((m) => !m.read).length;
    $('#unread').textContent = unread ? unread : '';
    $('#unread').style.display = unread ? 'inline-block' : 'none';
  }

  function syncFailSelect() { const s = $('#failMode'); if (s) s.value = S.failMode; }
  function syncSendFailSelect() { const s = $('#sendFail'); if (s) s.value = S.sendFail; }

  /* ---------------- 渲染：主表格 ---------------- */
  const VIEWS = [
    { id: 'mine', name: '我的未完成' },
    { id: 'byAssignee', name: '按人员查看' },
    { id: 'byProject', name: '按项目查看' },
    { id: 'byReporter', name: '按报告人查看' },
    { id: 'kanban', name: '状态看板' }
  ];
  const COLS = [
    ['key', 'Issue 编号'], ['title', '标题'], ['type', '类型'], ['project', '所属项目'],
    ['assignee', '经办人'], ['reporter', '报告人'], ['status', 'Jira 状态'], ['category', '状态类别'],
    ['priority', '优先级'], ['created', '创建时间'], ['updated', '最后更新时间'], ['synced', '最后同步时间']
  ];

  function cell(r, k) {
    switch (k) {
      case 'key': return `<a href="#" class="jira-link" data-key="${esc(r.key)}">${esc(r.key)}</a>`;
      case 'type': return `<span class="tag tag-${r.type.toLowerCase()}">${esc(r.type)}</span>`;
      case 'assignee': case 'reporter': return personHtml(r[k]);
      case 'category': return `<span class="pill ${r.category === 'todo' ? 'pill-gray' : 'pill-blue'}">${CATEGORY_LABEL[r.category]}</span>`;
      case 'status': return `<span class="status">${esc(r.status)}</span>`;
      case 'priority': return `<span class="prio prio-${esc(r.priority)}">${esc(r.priority)}</span>`;
      default: return esc(r[k]);
    }
  }

  function gridHtml(rows) {
    if (!rows.length) return `<div class="empty">没有记录</div>`;
    return `<table class="grid"><thead><tr><th class="rn">#</th>${COLS.map((c) => `<th>${c[1]}</th>`).join('')}</tr></thead><tbody>` +
      rows.map((r, i) => `<tr><td class="rn">${i + 1}</td>${COLS.map((c) => `<td class="ro" data-col="${c[0]}">${cell(r, c[0])}</td>`).join('')}</tr>`).join('') +
      `</tbody></table>`;
  }

  function grouped(rows, keyFn, labelFn) {
    const g = {};
    rows.forEach((r) => { const k = keyFn(r); (g[k] = g[k] || []).push(r); });
    const keys = Object.keys(g).sort((a, b) => g[b].length - g[a].length);
    if (!keys.length) return `<div class="empty">没有记录</div>`;
    return keys.map((k) => `<details class="group" open><summary><b>${esc(labelFn(k))}</b><span class="count">${g[k].length} 条</span>
      <span class="mini">${['Story', 'Task', 'Bug'].map((t) => `${t} ${g[k].filter((r) => r.type === t).length}`).join(' · ')}</span></summary>${gridHtml(g[k])}</details>`).join('');
  }

  function renderTable() {
    const me = fsUser(S.role);
    const admin = TABLE_ADMINS.includes(S.role);
    const rows = S.table.slice().sort((a, b) => a.created.localeCompare(b.created));
    let body = '';
    if (S.view === 'mine') {
      const mine = rows.filter((r) => { const f = jiraToFeishu(r.assignee); return f && f.id === S.role; });
      body = `<div class="view-hint">当前查看者：<b>${esc(me.name)}</b>，仅显示你作为经办人的未完成任务（共 ${mine.length} 条）。这是 17:00 提醒消息中"查看全部"链接打开的视图。</div>` + gridHtml(mine);
    } else if (S.view === 'byAssignee') {
      body = grouped(rows, (r) => r.assignee, (k) => '经办人：' + personName(k) + (jiraToFeishu(k) ? '' : '（未对应）'));
    } else if (S.view === 'byProject') {
      body = grouped(rows, (r) => r.project, (k) => '项目：' + k);
    } else if (S.view === 'byReporter') {
      body = grouped(rows, (r) => r.reporter, (k) => '报告人：' + personName(k) + (jiraToFeishu(k) ? '' : '（未对应）'));
    } else {
      body = `<div class="kanban">${['todo', 'inprogress'].map((c) => {
        const list = rows.filter((r) => r.category === c);
        return `<div class="col"><div class="col-h">${CATEGORY_LABEL[c]}<span class="count">${list.length}</span></div>${list.map((r) => `
          <div class="card"><div class="card-top"><a href="#" class="jira-link" data-key="${esc(r.key)}">${esc(r.key)}</a><span class="tag tag-${r.type.toLowerCase()}">${r.type}</span></div>
          <div class="card-title">${esc(r.title)}</div>
          <div class="card-meta">${personHtml(r.assignee)} · ${esc(r.status)}</div></div>`).join('') || '<div class="empty small">无</div>'}</div>`;
      }).join('')}</div>`;
    }

    const mr = S.manualResult;
    const resultBar = mr ? `<div class="result ${mr.ok ? 'ok' : 'err'}">${mr.ok
      ? `【手动同步】完成 · ${fmtShort(mr.at)} · 同步 ${mr.count} 条 · 成功${mr.retried ? '（首次失败，重试成功）' : ''}`
      : `【手动同步】失败 · ${fmtShort(mr.at)} · 已重试 1 次，已通知运维负责人与备份负责人；表格保持上次成功同步的数据`}<span class="muted result-note">显示至下一次手动同步</span></div>` : '';

    $('#main').innerHTML = `
      <div class="doc-head">
        <div class="crumb">知识库 · 「敏捷项目管理」所在空间 / <b>QD助手 · Jira 未完成任务</b></div>
        <div class="doc-title-row">
          <h1>QD助手 · Jira 未完成任务</h1>
          <span class="pill pill-gray">🔒 只读 · 数据来自 Jira 看板 22909</span>
          <span class="pill pill-gray">所有者：朱卓</span>
          <div class="spacer"></div>
          <span class="last-sync">最后同步：<b>${esc(fmtShort(S.lastSync))}</b></span>
          <button class="btn ${admin ? 'btn-primary' : 'btn-disabled'}" data-act="manualSync" title="${admin ? '立即从 Jira 同步（只更新表格，不发送提醒）' : '仅表格管理员或负责人可操作'}">⟳ 立即同步</button>
        </div>
        ${resultBar}
        <details class="guide"><summary>📖 视图使用说明</summary>
          <ul>
            <li><b>研发 / 测试</b>：看「我的未完成」，收到 17:00 提醒后点"查看全部"也会打开这里。</li>
            <li><b>测试</b>：看「按报告人查看」，跟进自己提交的问题。</li>
            <li><b>项目负责人</b>：看「按人员查看」「按项目查看」「状态看板」。</li>
            <li>表格只保留<b>未完成</b>任务；在 Jira 中改为完成、移出看板或删除后，下次同步即从表格消失。所有修改请在 Jira 中完成。</li>
          </ul>
        </details>
      </div>
      <div class="views">${VIEWS.map((v) => `<button class="${S.view === v.id ? 'active' : ''}" data-view="${v.id}">${v.name}</button>`).join('')}
        <div class="spacer"></div><span class="muted">共 ${S.table.length} 条未完成任务</span></div>
      <div class="view-body">${body}</div>`;
  }

  /* ---------------- 渲染：飞书消息 ---------------- */
  function remindCard(m) {
    const rows = m.snapshot;
    const sec = ['Story', 'Task', 'Bug'].map((t) => {
      const list = rows.filter((r) => r.type === t).sort((a, b) => a.created.localeCompare(b.created));
      const top = list.slice(0, 3);
      return `<div class="sec"><div class="sec-h">${t}（共 ${list.length} 条）</div>${top.map((r) =>
        `<div class="item">· <a href="#" class="jira-link" data-key="${esc(r.key)}">${esc(r.key)}</a> ${esc(r.title)} —— ${esc(r.status)}</div>`).join('')}</div>`;
    }).join('');
    return `<div class="card-msg">
      <div class="card-msg-h">📋 Jira 待更新提醒</div>
      <div class="card-msg-b">
        <div>请在下班前更新以下任务在 Jira 中的状态：</div>
        ${sec}
        <a href="#" class="view-all" data-act="openMine">查看全部 → 我的未完成</a>
        <div class="data-at">数据截至 ${esc(fmtShort(m.dataAt))}</div>
      </div></div>`;
  }

  function renderInbox() {
    const me = fsUser(S.role);
    const msgs = (S.inbox[S.role] || []).slice().reverse();
    msgs.forEach((m) => (m.read = true));
    let reason = '';
    if (!msgs.length) {
      const myAccs = M.jiraUsers.filter((ju) => { const f = jiraToFeishu(ju.acc); return f && f.id === S.role; }).map((j) => j.acc);
      const excluded = myAccs.some((a) => S.mgmt.exclude.find((e) => e.acc === a));
      const hasTasks = S.table.some((r) => myAccs.includes(r.assignee));
      reason = excluded ? '你在排除名单中，不会收到提醒。'
        : !myAccs.length ? '你的飞书账号与任何 Jira 账号都对应不上（可在管理表格「例外对应表」中配置）。'
        : !hasTasks ? '你名下没有未完成任务，当天不会收到提醒。'
        : '还没有到 17:00 提醒，可在顶部点「▶ 17:00 发送提醒」模拟。';
    }
    $('#main').innerHTML = `
      <div class="chat">
        <div class="chat-side">
          <div class="chat-item active"><span class="bot">QD</span><div><b>QD助手</b><div class="muted">机器人 · 私聊</div></div></div>
        </div>
        <div class="chat-main">
          <div class="chat-h"><b>QD助手</b><span class="muted"> · 与 ${esc(me.name)} 的私聊</span></div>
          <div class="chat-body">${msgs.length ? msgs.map((m) => `
            <div class="msg"><span class="bot">QD</span><div class="msg-c">
              <div class="msg-meta">QD助手 · ${esc(fmtShort(m.at))}</div>
              ${m.kind === 'remind' ? remindCard(m) : `<div class="card-msg alert"><div class="card-msg-h">⚠️ ${esc(m.title)}</div><div class="card-msg-b pre">${esc(m.body)}</div></div>`}
            </div></div>`).join('') : `<div class="empty">暂无消息。${esc(reason)}</div>`}</div>
        </div>
      </div>`;
  }

  /* ---------------- 渲染：管理表格 ---------------- */
  function renderMgmt() {
    if (!MGMT_ACCESS.includes(S.role)) {
      $('#main').innerHTML = `<div class="noperm"><div class="big">🔒</div><h2>无权限访问管理表格</h2>
        <p>管理表格仅运维负责人（胡桓康）与备份负责人（朱卓）可访问，其中包含人员邮箱等信息。</p>
        <p class="muted">可在顶部切换身份体验。</p></div>`;
      return;
    }
    const tabs = [['exclude', '排除名单'], ['exceptions', '例外对应表'], ['holidays', '节假日日历']];
    let body = '';
    if (S.mgmtTab === 'exclude') {
      body = `<p class="muted">名单中的人员不接收 17:00 提醒。修改后下一次提醒即生效。</p>
        <table class="grid edit"><thead><tr><th>Jira 账号</th><th>姓名</th><th>原因</th><th></th></tr></thead><tbody>
        ${S.mgmt.exclude.map((e, i) => `<tr><td>${esc(e.acc)}</td><td>${esc(jiraUser(e.acc).name)}</td><td>${esc(e.reason)}</td><td><button class="link danger" data-del="exclude" data-i="${i}">删除</button></td></tr>`).join('') || '<tr><td colspan="4" class="empty small">暂无</td></tr>'}
        </tbody></table>
        <div class="add-row"><select id="exAcc">${M.jiraUsers.filter((u) => !S.mgmt.exclude.find((e) => e.acc === u.acc)).map((u) => `<option value="${u.acc}">${esc(u.name)}（${u.acc}）</option>`).join('')}</select>
        <input id="exReason" placeholder="原因（选填）"><button class="btn btn-primary" data-act="addExclude">＋ 添加</button></div>`;
    } else if (S.mgmtTab === 'exceptions') {
      const unmatched = M.jiraUsers.filter((u) => !M.feishuUsers.find((f) => f.email === u.email));
      body = `<p class="muted">默认按公司邮箱把 Jira 账号对应到飞书账号；对不上的人员在这里手工补充。</p>
        <div class="callout">邮箱对不上的 Jira 账号：${unmatched.map((u) => `<b>${esc(u.name)}</b>（${esc(u.acc)} · ${esc(u.email)}）${S.mgmt.exceptions.find((e) => e.acc === u.acc) ? ' ✅已配置' : ''}`).join('，')}</div>
        <table class="grid edit"><thead><tr><th>Jira 账号</th><th>Jira 邮箱</th><th>→ 飞书账号</th><th>备注</th><th></th></tr></thead><tbody>
        ${S.mgmt.exceptions.map((e, i) => `<tr><td>${esc(e.acc)}</td><td>${esc(jiraUser(e.acc).email)}</td><td>${esc((fsUser(e.fsId) || {}).name || e.fsId)}（${esc((fsUser(e.fsId) || {}).email || '')}）</td><td>${esc(e.note || '')}</td><td><button class="link danger" data-del="exceptions" data-i="${i}">删除</button></td></tr>`).join('') || '<tr><td colspan="5" class="empty small">暂无</td></tr>'}
        </tbody></table>
        <div class="add-row"><select id="mapAcc">${M.jiraUsers.map((u) => `<option value="${u.acc}">${esc(u.name)}（${u.acc}）</option>`).join('')}</select> →
        <select id="mapFs">${M.feishuUsers.map((u) => `<option value="${u.id}">${esc(u.name)}（${esc(u.email)}）</option>`).join('')}</select>
        <input id="mapNote" placeholder="备注（选填）"><button class="btn btn-primary" data-act="addMap">＋ 添加</button></div>`;
    } else {
      const y0 = +S.date.slice(0, 4);
      const yrs = [y0, y0 + 1].map((y) => `${y} 年：${yearEntered(y) ? '✅ 已录入' : '⚠️ 未录入（按周一至周五判断）'}`).join('　｜　');
      body = `<p class="muted">用于判断法定工作日：<b>法定节假日</b>不定时同步、不发提醒；<b>调休补班日</b>照常执行；<b>未录入的年份</b>按周一至周五判断。每年 12 月 1 日起，每周一 09:00 检查次年日历，未录入会提醒运维负责人与备份负责人。以下为示例数据，正式使用需按国务院公布的安排录入。</p>
        <div class="callout">${yrs}</div>
        <table class="grid edit"><thead><tr><th>日期</th><th>类型</th><th>名称</th><th></th></tr></thead><tbody>
        ${S.mgmt.holidays.slice().sort((a, b) => a.date.localeCompare(b.date)).map((h) => `<tr><td>${esc(dateLabel(h.date))}</td><td><span class="pill ${h.type === 'holiday' ? 'pill-gray' : 'pill-ok'}">${h.type === 'holiday' ? '法定节假日' : '调休补班'}</span></td><td>${esc(h.name)}</td><td><button class="link danger" data-del="holidays" data-date="${h.date}">删除</button></td></tr>`).join('')}
        </tbody></table>
        <div class="add-row"><input type="date" id="hDate"><select id="hType"><option value="holiday">法定节假日</option><option value="makeup">调休补班</option></select>
        <input id="hName" placeholder="名称"><button class="btn btn-primary" data-act="addHoliday">＋ 添加</button></div>`;
    }
    $('#main').innerHTML = `
      <div class="doc-head"><div class="crumb">管理表格（仅运维负责人与备份负责人可访问）</div>
        <div class="doc-title-row"><h1>QD助手 · 管理表格</h1><span class="pill pill-gray">🔐 含人员邮箱，不对知识库成员公开</span></div></div>
      <div class="views">${tabs.map((t) => `<button class="${S.mgmtTab === t[0] ? 'active' : ''}" data-mtab="${t[0]}">${t[1]}</button>`).join('')}</div>
      <div class="view-body">${body}</div>`;
  }

  /* ---------------- 渲染：模拟 Jira ---------------- */
  function renderJira() {
    const list = S.jira.filter((i) => !i.deleted);
    $('#main').innerHTML = `
      <div class="jira-banner">🧪 <b>模拟 Jira（数据源）</b>：仅用于原型演示，不是本系统的功能。在这里修改任务后，需要等下一次同步（定时或"立即同步"），主表格才会变化。</div>
      <table class="grid jira"><thead><tr><th>编号</th><th>标题</th><th>类型</th><th>项目</th><th>经办人</th><th>报告人</th><th>状态</th><th>状态类别</th><th>在看板 22909 中</th><th>创建时间</th><th>最后更新</th><th></th></tr></thead><tbody>
      ${list.map((i) => `<tr class="${statusCat(i.status) === 'done' || !i.inBoard ? 'dim' : ''}">
        <td>${esc(i.key)}</td><td>${esc(i.title)}</td><td><span class="tag tag-${i.type.toLowerCase()}">${i.type}</span></td><td>${esc(i.project)}</td>
        <td><select data-edit="assignee" data-key="${i.key}">${M.jiraUsers.map((u) => `<option value="${u.acc}" ${u.acc === i.assignee ? 'selected' : ''}>${esc(u.name)}</option>`).join('')}</select></td>
        <td>${esc(jiraUser(i.reporter).name)}</td>
        <td><select data-edit="status" data-key="${i.key}">${M.statuses.map((s) => `<option ${s.name === i.status ? 'selected' : ''}>${s.name}</option>`).join('')}</select></td>
        <td><span class="pill ${statusCat(i.status) === 'done' ? 'pill-ok' : statusCat(i.status) === 'todo' ? 'pill-gray' : 'pill-blue'}">${CATEGORY_LABEL[statusCat(i.status)]}</span></td>
        <td><input type="checkbox" data-edit="inBoard" data-key="${i.key}" ${i.inBoard ? 'checked' : ''}></td>
        <td>${esc(fmtShort(i.created))}</td><td>${esc(fmtShort(i.updated))}</td>
        <td><button class="link danger" data-act="jiraDel" data-key="${i.key}">删除</button></td></tr>`).join('')}
      </tbody></table>
      <div class="add-row">新建 Issue：<input id="nTitle" placeholder="标题"><select id="nType"><option>Story</option><option>Task</option><option>Bug</option></select>
        <select id="nAssignee">${M.jiraUsers.map((u) => `<option value="${u.acc}">${esc(u.name)}</option>`).join('')}</select>
        <button class="btn btn-primary" data-act="jiraAdd">＋ 新建</button></div>`;
  }

  /* ---------------- Jira 详情弹窗 ---------------- */
  function openJira(key) {
    const i = S.jira.find((x) => x.key === key);
    if (!i) return;
    $('#modal').innerHTML = `<div class="modal-box"><div class="modal-h">🔗 Jira · ${esc(i.key)}<button class="x" data-act="closeModal">×</button></div>
      <div class="modal-b"><h3>${esc(i.title)}</h3>
      <dl><dt>类型</dt><dd>${esc(i.type)}</dd><dt>状态</dt><dd>${esc(i.status)}（${CATEGORY_LABEL[statusCat(i.status)]}）</dd>
      <dt>经办人</dt><dd>${esc(jiraUser(i.assignee).name)}</dd><dt>报告人</dt><dd>${esc(jiraUser(i.reporter).name)}</dd>
      <dt>优先级</dt><dd>${esc(i.priority)}</dd><dt>创建时间</dt><dd>${esc(i.created)}</dd><dt>最后更新</dt><dd>${esc(i.updated)}</dd></dl>
      <p class="muted">原型中以弹窗模拟跳转 Jira（真实地址形如 https://jira.dreame.tech/browse/${esc(i.key)}）。状态请在 Jira 中更新，可到「模拟 Jira」页修改。</p>
      </div></div>`;
    $('#modal').classList.add('show');
  }

  /* ---------------- 总渲染 ---------------- */
  function render() {
    renderTop();
    if (S.tab === 'table') renderTable();
    else if (S.tab === 'inbox') renderInbox();
    else if (S.tab === 'mgmt') renderMgmt();
    else renderJira();
    renderTop(); // 刷新未读数
  }

  /* ---------------- 事件 ---------------- */
  document.addEventListener('click', (e) => {
    const t = e.target.closest('[data-tab],[data-view],[data-mtab],[data-run],[data-act],[data-del],.jira-link,td.ro');
    if (!t) return;
    if (t.matches('.jira-link')) { e.preventDefault(); openJira(t.dataset.key); return; }
    if (t.matches('td.ro')) { toast('只读：数据来自 Jira，请在 Jira 中修改', 'warn'); return; }
    if (t.dataset.tab) { S.tab = t.dataset.tab; return render(); }
    if (t.dataset.view) { S.view = t.dataset.view; return render(); }
    if (t.dataset.mtab) { S.mgmtTab = t.dataset.mtab; return render(); }
    if (t.dataset.run) {
      const r = t.dataset.run;
      if (r === 'all') { calendarCheck(); scheduledSync('09:00'); scheduledSync('16:50'); sendReminders(); }
      else if (r === '09:00') { calendarCheck(); scheduledSync('09:00'); }
      else if (r === '17:00') sendReminders();
      else scheduledSync(r);
      return render();
    }
    if (t.dataset.del) {
      const k = t.dataset.del;
      if (k === 'holidays') S.mgmt.holidays = S.mgmt.holidays.filter((h) => h.date !== t.dataset.date);
      else S.mgmt[k].splice(+t.dataset.i, 1);
      toast('已删除，下一次执行即按新配置生效', 'ok');
      return render();
    }
    const a = t.dataset.act;
    e.preventDefault();
    if (a === 'manualSync') {
      if (!TABLE_ADMINS.includes(S.role)) { toast('仅表格管理员或负责人可操作"立即同步"', 'warn'); return; }
      const now = new Date();
      const hh = String(now.getHours()).padStart(2, '0'), mm = String(now.getMinutes()).padStart(2, '0');
      doSync('手动同步', `${hh}:${mm}`);
      toast('手动同步只更新表格，不发送提醒', '');
    } else if (a === 'closeResult') S.manualResult = null;
    else if (a === 'openMine') { S.tab = 'table'; S.view = 'mine'; }
    else if (a === 'closeModal') { $('#modal').classList.remove('show'); return; }
    else if (a === 'nextDay') { const d = new Date(S.date + 'T00:00:00'); d.setDate(d.getDate() + 1); S.date = ymd(d); }
    else if (a === 'addExclude') {
      const acc = $('#exAcc').value; if (!acc) return;
      S.mgmt.exclude.push({ acc, reason: $('#exReason').value || '—' }); toast('已加入排除名单', 'ok');
    } else if (a === 'addMap') {
      const acc = $('#mapAcc').value;
      S.mgmt.exceptions = S.mgmt.exceptions.filter((x) => x.acc !== acc);
      S.mgmt.exceptions.push({ acc, fsId: $('#mapFs').value, note: $('#mapNote').value }); toast('已保存例外对应', 'ok');
    } else if (a === 'addHoliday') {
      const d = $('#hDate').value; if (!d) { toast('请选择日期', 'warn'); return; }
      S.mgmt.holidays = S.mgmt.holidays.filter((h) => h.date !== d);
      S.mgmt.holidays.push({ date: d, type: $('#hType').value, name: $('#hName').value || '（未命名）' }); toast('已保存', 'ok');
    } else if (a === 'jiraDel') {
      const i = S.jira.find((x) => x.key === t.dataset.key); i.deleted = true; toast(`已在 Jira 中删除 ${i.key}（下次同步后从主表格移除）`, '');
    } else if (a === 'jiraAdd') {
      const title = $('#nTitle').value.trim(); if (!title) { toast('请输入标题', 'warn'); return; }
      const n = 400 + S.jira.length;
      S.jira.push({ key: 'VERA2506M-' + n, title, type: $('#nType').value, project: 'VERA2506M', assignee: $('#nAssignee').value, reporter: 'zhuzhuo', status: '待处理', priority: '中', created: `${S.date} 10:00`, updated: `${S.date} 10:00`, inBoard: true });
      toast('已在 Jira 中新建（下次同步后出现在主表格）', 'ok');
    } else if (a === 'reset') { location.reload(); return; }
    render();
  });

  document.addEventListener('change', (e) => {
    const t = e.target;
    if (t.id === 'role') { S.role = t.value; return render(); }
    if (t.id === 'date') { if (t.value) S.date = t.value; return render(); }
    if (t.id === 'sendFail') { S.sendFail = t.value; toast(t.value === 'none' ? '已恢复正常' : '下一次 17:00 发送给王芳时将模拟出错', 'warn'); return; }
    if (t.id === 'failMode') { S.failMode = t.value; toast(t.value === 'none' ? '已恢复正常' : '下一次同步将模拟失败', 'warn'); return; }
    if (t.dataset.edit) {
      const i = S.jira.find((x) => x.key === t.dataset.key);
      const k = t.dataset.edit;
      i[k] = k === 'inBoard' ? t.checked : t.value;
      i.updated = `${S.date} ${new Date().toTimeString().slice(0, 5)}`;
      toast(`Jira 已更新 ${i.key}，等待下一次同步`, '');
      render();
    }
  });

  function ymd(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; }

  /* ---------------- 初始化：模拟当天 09:00 已完成一次定时同步 ---------------- */
  doSync('09:00 定时同步', '09:00', true);
  S.ran[`${S.date}|09:00`] = true;
  render();
})();
