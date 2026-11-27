package com.dreame.qd.service;

import com.dreame.qd.integration.feishu.AdminConfigRepository;
import com.dreame.qd.integration.feishu.ContactGateway;
import com.dreame.qd.integration.feishu.TaskTableGateway;
import com.dreame.qd.integration.jira.JiraClient;
import com.dreame.qd.model.ErrorCode;
import com.dreame.qd.model.Models.JiraIssue;
import com.dreame.qd.model.Models.JiraUser;
import com.dreame.qd.model.Models.PersonRef;
import com.dreame.qd.model.Models.SyncResult;
import com.dreame.qd.model.Models.SyncTrigger;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.QdException;
import com.dreame.qd.rule.AccountMapper;
import com.dreame.qd.rule.TaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INT-04 执行同步：Jira 拉取 → 账号对应 → 未完成集合（R2）→ 主表格按 Issue 编号整体对齐（新增 → 更新 → 删除）。
 * 同一时刻只允许一个同步；失败按 04 1.6 重试 1 次；重试后仍失败 → 结果对象 success=false 并调用异常通知；
 * 失败时主表格保持原数据（写表前的任何一步失败都不会改动表格）。整体对齐可重入。
 */
public class SyncService {
    public static final String BUSY = "BUSY";
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final JiraClient jira;
    private final ContactGateway contacts;
    private final AdminConfigRepository adminConfig;
    private final TaskTableGateway table;
    private final TaskMapper taskMapper;
    private final RetryExecutor retry;
    private final AlertService alerts;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Instant lastSuccessAt;

    public SyncService(JiraClient jira, ContactGateway contacts, AdminConfigRepository adminConfig, TaskTableGateway table,
                       TaskMapper taskMapper, RetryExecutor retry, AlertService alerts, Clock clock) {
        this.jira = jira;
        this.contacts = contacts;
        this.adminConfig = adminConfig;
        this.table = table;
        this.taskMapper = taskMapper;
        this.retry = retry;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** 最近一次成功同步的开始时间（本进程内）；未同步过为空。 */
    public Instant lastSuccessAt() { return lastSuccessAt; }

    public boolean isRunning() { return lock.isLocked(); }

    public SyncResult sync(SyncTrigger trigger) {
        if (!lock.tryLock()) {
            log.info("{}：已有同步正在进行，本次请求忽略", trigger.label());
            return new SyncResult(trigger, false, BUSY, 0, 0, "已有同步正在进行，本次请求已忽略", clock.instant());
        }
        try {
            Instant startedAt = clock.instant();
            long t0 = System.nanoTime();
            var outcome = retry.run(() -> doSync(startedAt));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (outcome.success()) {
                lastSuccessAt = startedAt;
                String msg = trigger.label() + "完成：" + outcome.value() + " 条未完成任务"
                        + (outcome.retried() > 0 ? "（首次失败，重试成功）" : "");
                log.info("{}，耗时 {} ms", msg, ms);
                return new SyncResult(trigger, true, "OK", outcome.value(), outcome.retried(), msg, clock.instant());
            }
            QdException e = outcome.failure();
            log.error("{}失败 {}，耗时 {} ms", trigger.label(), e.summary(), ms);
            SyncResult failed = new SyncResult(trigger, false, e.errorCode().code(), 0, outcome.retried(), e.getMessage(), clock.instant());
            alerts.syncFailed(failed, lastSuccessAt);
            return failed;
        } catch (RuntimeException e) {
            log.error("{}发生未预期错误：{}", trigger.label(), e.toString(), e);
            SyncResult failed = new SyncResult(trigger, false, ErrorCode.SYS.code(), 0, 0, "未预期错误：" + e.getClass().getSimpleName(), clock.instant());
            alerts.syncFailed(failed, lastSuccessAt);
            return failed;
        } finally {
            lock.unlock();
        }
    }

    /** @return 同步后主表格的记录数 */
    int doSync(Instant syncedAt) {
        List<JiraIssue> issues = jira.fetchUnfinishedIssues();

        // 账号对应（R3）：先按邮箱，再查例外对应表
        Map<String, JiraUser> users = new LinkedHashMap<>();
        for (JiraIssue i : issues) {
            for (JiraUser u : new JiraUser[] {i.assignee(), i.reporter()}) {
                if (u != null) users.putIfAbsent(AccountMapper.normalize(u.account()), u);
            }
        }
        Set<String> emails = new LinkedHashSet<>();
        users.values().forEach(u -> { if (u.email() != null) emails.add(u.email()); });
        Map<String, String> byEmail = emails.isEmpty() ? Map.of() : contacts.findOpenIdsByEmail(emails);
        Map<String, String> byAccount = users.values().stream().anyMatch(u -> !byEmail.containsKey(AccountMapper.normalize(u.email())))
                ? adminConfig.loadMappings() : Map.of();
        Map<String, PersonRef> people = AccountMapper.map(users.values(), byEmail, byAccount);

        // 计算应在表中的行（以 Issue 编号去重）
        Map<String, TaskRow> desired = new LinkedHashMap<>();
        for (JiraIssue i : issues) {
            PersonRef assignee = i.assignee() == null ? null : people.get(AccountMapper.normalize(i.assignee().account()));
            PersonRef reporter = i.reporter() == null ? null : people.get(AccountMapper.normalize(i.reporter().account()));
            taskMapper.toRow(i, assignee, reporter, syncedAt).ifPresent(r -> desired.put(r.key(), r));
        }

        align("主表格", table, desired);
        for (int i = 0; i < mirrors.size(); i++) align("副本表格 " + (i + 1), mirrors.get(i), desired);
        return desired.size();
    }

    /** 同一份结果写入副本表格（如"我的未完成任务"，行级权限不同）；任一表格写入失败都按同步失败处理，整体对齐可重入。 */
    private volatile List<TaskTableGateway> mirrors = List.of();

    public void setMirrors(List<TaskTableGateway> mirrors) { this.mirrors = List.copyOf(mirrors); }

    /** 与指定表格现状按 Issue 编号整体对齐（新增 → 更新 → 删除）。 */
    private void align(String label, TaskTableGateway table, Map<String, TaskRow> desired) {
        List<TaskRow> existing = table.listAll();
        Map<String, TaskRow> existingByKey = new HashMap<>();
        List<String> deleteIds = new ArrayList<>();
        for (TaskRow r : existing) {
            if (r.key().isEmpty() || existingByKey.putIfAbsent(r.key(), r) != null) deleteIds.add(r.recordId()); // 空行、重复行
        }
        List<TaskRow> toCreate = new ArrayList<>();
        List<TaskRow> toUpdate = new ArrayList<>();
        desired.forEach((key, row) -> {
            TaskRow old = existingByKey.get(key);
            if (old == null) toCreate.add(row); else toUpdate.add(row.withRecordId(old.recordId()));
        });
        existingByKey.forEach((key, old) -> { if (!desired.containsKey(key)) deleteIds.add(old.recordId()); });

        table.createBatch(toCreate);
        table.updateBatch(toUpdate);
        table.deleteBatch(deleteIds);
        log.info("{}对齐：新增 {}，更新 {}，删除 {}", label, toCreate.size(), toUpdate.size(), deleteIds.size());
    }
}
