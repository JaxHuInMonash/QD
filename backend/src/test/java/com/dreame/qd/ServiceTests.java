package com.dreame.qd;

import static org.assertj.core.api.Assertions.assertThat;

import com.dreame.qd.integration.feishu.AdminConfigRepository;
import com.dreame.qd.integration.feishu.CardBuilder;
import com.dreame.qd.integration.feishu.ContactGateway;
import com.dreame.qd.integration.feishu.MessageGateway;
import com.dreame.qd.integration.feishu.TaskTableGateway;
import com.dreame.qd.integration.jira.JiraClient;
import com.dreame.qd.integration.local.DailyStateStore;
import com.dreame.qd.model.ErrorCode;
import com.dreame.qd.model.Models.AdminConfig;
import com.dreame.qd.model.Models.Holiday;
import com.dreame.qd.model.Models.JiraIssue;
import com.dreame.qd.model.Models.JiraUser;
import com.dreame.qd.model.Models.ReminderResult;
import com.dreame.qd.model.Models.SyncResult;
import com.dreame.qd.model.Models.SyncTrigger;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.QdException;
import com.dreame.qd.rule.ReminderComposer;
import com.dreame.qd.rule.TaskMapper;
import com.dreame.qd.service.AlertService;
import com.dreame.qd.service.ReminderService;
import com.dreame.qd.service.RetryExecutor;
import com.dreame.qd.service.SyncService;
import com.dreame.qd.trigger.CatchUpRunner;
import com.dreame.qd.trigger.ManualSyncHandler;
import com.dreame.qd.trigger.ScheduledJobs;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceTests {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    final ObjectMapper mapper = new ObjectMapper();
    final TaskMapper taskMapper = RuleTests.MAPPER;

    // ---------- 假实现 ----------
    static class FakeJira implements JiraClient {
        List<JiraIssue> issues = new ArrayList<>();
        Map<String, JiraUser> users = new HashMap<>();
        java.util.Deque<QdException> failures = new java.util.ArrayDeque<>();
        int calls;
        public List<JiraIssue> fetchUnfinishedIssues() {
            calls++;
            if (!failures.isEmpty()) throw failures.poll();
            return issues;
        }
        public Optional<JiraUser> findUser(String account) { return Optional.ofNullable(users.get(account)); }
    }

    static class FakeTable implements TaskTableGateway {
        List<TaskRow> rows = new ArrayList<>();
        List<TaskRow> created = new ArrayList<>(), updated = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        int seq;
        public List<TaskRow> listAll() { return new ArrayList<>(rows); }
        public void createBatch(List<TaskRow> r) { created.addAll(r); r.forEach(x -> rows.add(x.withRecordId("rec" + (++seq)))); }
        public void updateBatch(List<TaskRow> r) { updated.addAll(r); }
        public void deleteBatch(List<String> ids) { deleted.addAll(ids); rows.removeIf(x -> ids.contains(x.recordId())); }
    }

    static class FakeContacts implements ContactGateway {
        Map<String, String> byEmail = new HashMap<>();
        public Map<String, String> findOpenIdsByEmail(java.util.Collection<String> emails) {
            Map<String, String> out = new HashMap<>();
            emails.forEach(e -> { if (byEmail.containsKey(e.toLowerCase())) out.put(e.toLowerCase(), byEmail.get(e.toLowerCase())); });
            return out;
        }
    }

    static class FakeMessages implements MessageGateway {
        List<String[]> sent = new ArrayList<>();               // [openId, card]
        Map<String, java.util.Queue<QdException>> failures = new HashMap<>();
        public void sendCard(String openId, String card, String uuid) {
            var q = failures.get(openId);
            if (q != null && !q.isEmpty()) throw q.poll();
            sent.add(new String[] {openId, card});
        }
        List<String[]> to(String openId) { return sent.stream().filter(s -> s[0].equals(openId)).toList(); }
    }

    static class FakeAdmin extends AdminConfigRepository {
        AdminConfig config = new AdminConfig(Set.of(), Map.of(), List.of());
        QdException failure;
        FakeAdmin() { super(null, SupportTests.complete()); }
        @Override public AdminConfig load() { if (failure != null) throw failure; return config; }
        @Override public Map<String, String> loadMappings() { return load().mappings(); }
    }

    FakeJira jira = new FakeJira();
    FakeTable table = new FakeTable();
    FakeContacts contacts = new FakeContacts();
    FakeMessages messages = new FakeMessages();
    FakeAdmin admin = new FakeAdmin();
    Clock clock = Clock.fixed(Instant.parse("2026-11-26T08:50:00Z"), ZONE);          // 北京时间 16:50
    RetryExecutor retry = new RetryExecutor(Duration.ZERO, ms -> { });
    AlertService alerts;
    SyncService sync;

    @BeforeEach
    void setUp() {
        var p = SupportTests.complete();
        alerts = new AlertService(messages, new CardBuilder(mapper, ZONE), p, clock);
        sync = new SyncService(jira, contacts, admin, table, taskMapper, retry, alerts, clock);
        contacts.byEmail.put("a@dreame.tech", "ou_a");
        contacts.byEmail.put("b@dreame.tech", "ou_b");
    }

    JiraUser user(String acc, String name) { return new JiraUser(acc, name, acc + "@dreame.tech"); }

    JiraIssue issue(String key, String type, JiraUser assignee) {
        return new JiraIssue(key, "标题" + key, type, key.split("-")[0], assignee, user("zz", "朱卓"), "开发中", "indeterminate", "高",
                Instant.parse("2026-10-08T02:12:00Z"), Instant.parse("2026-11-24T10:30:00Z"));
    }

    // ---------- SyncService（INT-04） ----------
    @Test
    void syncCreatesUpdatesAndDeletesByIssueKey() {
        table.rows.add(RuleTests.row("OLD-1", com.dreame.qd.model.Models.TaskType.STORY, "ou_a", "甲", "2026-10-01T00:00:00Z").withRecordId("recOld"));
        table.rows.add(RuleTests.row("KEEP-1", com.dreame.qd.model.Models.TaskType.TASK, "ou_a", "甲", "2026-10-01T00:00:00Z").withRecordId("recKeep"));
        table.rows.add(RuleTests.row("KEEP-1", com.dreame.qd.model.Models.TaskType.TASK, "ou_a", "甲", "2026-10-01T00:00:00Z").withRecordId("recDup")); // 重复行
        jira.issues = List.of(issue("KEEP-1", "任务", user("a", "甲")), issue("NEW-1", "Story", user("b", "乙")),
                issue("SUB-1", "子任务", user("a", "甲")), issue("DONE-1", "Story", user("a", "甲")));
        jira.issues = new ArrayList<>(jira.issues);
        // DONE-1 改为完成类
        JiraIssue d = jira.issues.get(3);
        jira.issues.set(3, new JiraIssue(d.key(), d.summary(), d.typeName(), d.projectKey(), d.assignee(), d.reporter(), "完成", "done", d.priority(), d.created(), d.updated()));

        SyncResult r = sync.sync(SyncTrigger.SCHEDULED_1650);

        assertThat(r.success()).isTrue();
        assertThat(r.code()).isEqualTo("OK");
        assertThat(r.count()).isEqualTo(2);
        assertThat(table.created).extracting(TaskRow::key).containsExactly("NEW-1");
        assertThat(table.updated).extracting(TaskRow::recordId).containsExactly("recKeep");
        assertThat(table.deleted).containsExactlyInAnyOrder("recOld", "recDup");
        assertThat(table.created.get(0).assigneeOpenId()).isEqualTo("ou_b");
        assertThat(table.created.get(0).syncedAt()).isEqualTo(clock.instant());
        assertThat(sync.lastSuccessAt()).isEqualTo(clock.instant());
    }

    @Test
    void syncUsesExceptionMappingAndMarksUnmapped() {
        admin.config = new AdminConfig(Set.of(), Map.of("chenchen", "ou_c"), List.of());
        jira.issues = List.of(issue("X-1", "Story", new JiraUser("chenchen", "陈晨", "chen@partner.org")),
                issue("X-2", "Story", new JiraUser("sunwei", "孙伟", "sunwei@dreame.tech")),
                issue("X-3", "Story", null));
        sync.sync(SyncTrigger.MANUAL);
        Map<String, TaskRow> by = new LinkedHashMap<>();
        table.created.forEach(r -> by.put(r.key(), r));
        assertThat(by.get("X-1").assigneeOpenId()).isEqualTo("ou_c");
        assertThat(by.get("X-2").assigneeOpenId()).isNull();
        assertThat(by.get("X-2").assigneeDisplay()).isEqualTo("孙伟（未对应）");
        assertThat(by.get("X-3").assigneeDisplay()).isEqualTo("未分配");
    }

    @Test
    void transientFailureIsRetriedOnceThenSucceeds() {
        jira.failures.add(new QdException(ErrorCode.JIRA_SERVER, true, "5xx"));
        jira.issues = List.of(issue("A-1", "Story", user("a", "甲")));
        SyncResult r = sync.sync(SyncTrigger.SCHEDULED_0900);
        assertThat(r.success()).isTrue();
        assertThat(r.retried()).isEqualTo(1);
        assertThat(jira.calls).isEqualTo(2);
        assertThat(messages.sent).isEmpty();             // 成功不通知
    }

    @Test
    void authFailureIsNotRetriedAndNotifiesOpsAndBackup() {
        jira.failures.add(new QdException(ErrorCode.JIRA_AUTH, false, "Jira 认证失败（401）"));
        table.rows.add(RuleTests.row("KEEP-1", com.dreame.qd.model.Models.TaskType.TASK, "ou_a", "甲", "2026-10-01T00:00:00Z").withRecordId("recKeep"));
        SyncResult r = sync.sync(SyncTrigger.MANUAL);
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("QD-JIRA-001");
        assertThat(jira.calls).isEqualTo(1);                                    // 不重试
        assertThat(table.rows).hasSize(1);                                      // 主表格保持原数据
        assertThat(table.deleted).isEmpty();
        assertThat(messages.to("ou_ops")).hasSize(1);                           // 同时通知两人
        assertThat(messages.to("ou_backup")).hasSize(1);
        assertThat(messages.to("ou_ops").get(0)[1]).contains("认证失败未重试");
        assertThat(messages.to("ou_ops").get(0)[1]).doesNotContain("password");
    }

    @Test
    void secondFailureAfterRetryNotifies() {
        jira.failures.add(new QdException(ErrorCode.JIRA_TIMEOUT, true, "超时"));
        jira.failures.add(new QdException(ErrorCode.JIRA_TIMEOUT, true, "超时"));
        SyncResult r = sync.sync(SyncTrigger.SCHEDULED_1650);
        assertThat(r.success()).isFalse();
        assertThat(r.retried()).isEqualTo(1);
        assertThat(messages.sent).hasSize(2);
        assertThat(messages.to("ou_ops").get(0)[1]).contains("已自动重试 1 次仍失败");
    }

    @Test
    void concurrentSyncIsIgnored() throws Exception {
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        JiraClient blocking = new JiraClient() {
            public List<JiraIssue> fetchUnfinishedIssues() {
                inside.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return List.of();
            }
            public Optional<JiraUser> findUser(String a) { return Optional.empty(); }
        };
        SyncService s = new SyncService(blocking, contacts, admin, table, taskMapper, retry, alerts, clock);
        var ex = Executors.newSingleThreadExecutor();
        var first = ex.submit(() -> s.sync(SyncTrigger.SCHEDULED_0900));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        SyncResult second = s.sync(SyncTrigger.MANUAL);
        assertThat(second.code()).isEqualTo(SyncService.BUSY);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).success()).isTrue();
        ex.shutdown();
    }

    @Test
    void manualHandlerOnlyAcceptsKeywordAndDedupesMessages() {
        List<Runnable> queued = new ArrayList<>();
        var direct = new java.util.concurrent.AbstractExecutorService() {
            public void shutdown() {}
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t, TimeUnit u) { return true; }
            public void execute(Runnable r) { queued.add(r); r.run(); }
        };
        jira.issues = List.of();
        ManualSyncHandler h = new ManualSyncHandler(sync, mapper, direct);
        assertThat(h.onMessage("m1", "text", "{\"text\":\"你好\"}", "c", "user")).isFalse();
        assertThat(h.onMessage("m2", "text", "{\"text\":\"#QD立即同步\"}", "c", "user")).isTrue();
        assertThat(h.onMessage("m2", "text", "{\"text\":\"#QD立即同步\"}", "c", "user")).isFalse();   // 重复推送
        assertThat(jira.calls).isEqualTo(1);
    }

    // ---------- ReminderService（INT-11） ----------
    @Test
    void remindersSentOncePerPersonPerDay(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        table.rows = new ArrayList<>(List.of(
                RuleTests.row("A-1", com.dreame.qd.model.Models.TaskType.STORY, "ou_a", "甲", "2026-10-01T00:00:00Z"),
                RuleTests.row("B-1", com.dreame.qd.model.Models.TaskType.BUG, "ou_b", "乙", "2026-10-02T00:00:00Z"),
                RuleTests.row("C-1", com.dreame.qd.model.Models.TaskType.BUG, null, "孙伟（未对应）", "2026-10-03T00:00:00Z")));
        ReminderService rs = reminderService(state);
        LocalDate day = LocalDate.of(2026, 11, 26);

        ReminderResult r1 = rs.sendDailyReminders(day, false);
        assertThat(r1.sentCount()).isEqualTo(2);
        assertThat(r1.undelivered()).hasSize(1);                                        // 孙伟未对应
        assertThat(messages.to("ou_a")).hasSize(1);
        assertThat(messages.to("ou_a").get(0)[1]).contains("Story（共 1 条）").contains("Bug（共 0 条）").contains("数据截至 11-26 16:50");
        assertThat(messages.to("ou_ops")).hasSize(1);                                   // 未送达汇总通知运维
        assertThat(messages.to("ou_backup")).hasSize(1);

        ReminderResult r2 = rs.sendDailyReminders(day, true);                           // 补发：不重复
        assertThat(r2.sentCount()).isZero();
        assertThat(r2.skippedCount()).isEqualTo(2);
        assertThat(messages.to("ou_a")).hasSize(1);
        assertThat(messages.to("ou_b")).hasSize(1);
    }

    @Test
    void sendFailureRetriedOnceThenCountedUndelivered(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        table.rows = new ArrayList<>(List.of(
                RuleTests.row("A-1", com.dreame.qd.model.Models.TaskType.STORY, "ou_a", "甲", "2026-10-01T00:00:00Z"),
                RuleTests.row("B-1", com.dreame.qd.model.Models.TaskType.BUG, "ou_b", "乙", "2026-10-02T00:00:00Z"),
                RuleTests.row("D-1", com.dreame.qd.model.Models.TaskType.BUG, "ou_d", "丁", "2026-10-02T00:00:00Z")));
        var qa = new java.util.ArrayDeque<QdException>();
        qa.add(new QdException(ErrorCode.FEISHU_SEND, true, "网络抖动"));              // 重试成功
        messages.failures.put("ou_a", qa);
        var qb = new java.util.ArrayDeque<QdException>();
        qb.add(new QdException(ErrorCode.FEISHU_SEND, true, "网络抖动"));
        qb.add(new QdException(ErrorCode.FEISHU_SEND, true, "网络抖动"));              // 重试后仍失败
        messages.failures.put("ou_b", qb);
        var qd = new java.util.ArrayDeque<QdException>();
        qd.add(new QdException(ErrorCode.FEISHU_SEND, false, "该用户不在应用 QD助手 的可用范围内，无法私聊"));
        messages.failures.put("ou_d", qd);

        ReminderResult r = reminderService(state).sendDailyReminders(LocalDate.of(2026, 11, 26), false);

        assertThat(r.sentCount()).isEqualTo(1);
        assertThat(r.retriedCount()).isEqualTo(1);
        assertThat(r.undelivered()).extracting(u -> u.person()).containsExactlyInAnyOrder("乙", "丁");
        assertThat(r.undelivered().stream().filter(u -> u.person().equals("乙")).findFirst().get().reason()).contains("已自动重试 1 次");
        assertThat(r.undelivered().stream().filter(u -> u.person().equals("丁")).findFirst().get().reason()).doesNotContain("重试");  // 不可用账号不重试
        assertThat(state.isSent(LocalDate.of(2026, 11, 26), "ou_b")).isFalse();
    }

    @Test
    void excludedAccountsGetNoReminder(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        admin.config = new AdminConfig(Set.of("zhaolei", "sunwei", "yy"), Map.of("yy", "ou_y"), List.of());
        jira.users.put("zhaolei", new JiraUser("zhaolei", "赵磊", "zhaolei@dreame.tech"));
        jira.users.put("sunwei", new JiraUser("sunwei", "孙伟", "sunwei@dreame.tech"));
        contacts.byEmail.put("zhaolei@dreame.tech", "ou_z");
        table.rows = new ArrayList<>(List.of(
                RuleTests.row("A-1", com.dreame.qd.model.Models.TaskType.STORY, "ou_a", "甲", "2026-10-01T00:00:00Z"),
                RuleTests.row("Z-1", com.dreame.qd.model.Models.TaskType.STORY, "ou_z", "赵磊", "2026-10-01T00:00:00Z"),   // 按邮箱对应后排除
                RuleTests.row("Y-1", com.dreame.qd.model.Models.TaskType.STORY, "ou_y", "杨", "2026-10-01T00:00:00Z"),     // 按例外对应表排除
                RuleTests.row("S-1", com.dreame.qd.model.Models.TaskType.STORY, null, "孙伟（未对应）", "2026-10-01T00:00:00Z"))); // 未对应但被排除
        ReminderResult r = reminderService(state).sendDailyReminders(LocalDate.of(2026, 11, 26), false);
        assertThat(r.sentCount()).isEqualTo(1);
        assertThat(r.undelivered()).isEmpty();
        assertThat(messages.sent).hasSize(1);
    }

    ReminderService reminderService(DailyStateStore state) {
        return new ReminderService(table, admin, contacts, jira, new ReminderComposer(taskMapper, "https://mine", "https://view"),
                new CardBuilder(mapper, ZONE), messages, state, retry, alerts, clock);
    }

    // ---------- 触发层：定时任务与补执行 ----------
    @Test
    void scheduledJobsSkipNonWorkdays(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        admin.config = new AdminConfig(Set.of(), Map.of(), List.of(new Holiday(LocalDate.of(2026, 11, 26), false, "示例节假日")));
        ScheduledJobs jobs = new ScheduledJobs(sync, reminderService(state), admin, alerts, state, clock, MonthDay.of(12, 1));
        jobs.runSync1650();
        jobs.runRemind1700();
        assertThat(jira.calls).isZero();
        assertThat(messages.sent).isEmpty();
    }

    @Test
    void scheduledSyncRecordsRunOnlyOnSuccess(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        ScheduledJobs jobs = new ScheduledJobs(sync, reminderService(state), admin, alerts, state, clock, MonthDay.of(12, 1));
        jira.failures.add(new QdException(ErrorCode.JIRA_AUTH, false, "401"));
        jobs.runSync1650();
        assertThat(state.load(LocalDate.of(2026, 11, 26)).runs()).doesNotContainKey("sync1650");
        jobs.runSync1650();
        assertThat(state.load(LocalDate.of(2026, 11, 26)).runs()).containsKey("sync1650");
    }

    @Test
    void jobsSwallowUnexpectedExceptions(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        admin.failure = new QdException(ErrorCode.FEISHU_TIMEOUT, true, "读取失败");
        table.rows = new ArrayList<>();
        ScheduledJobs jobs = new ScheduledJobs(sync, reminderService(state), admin, alerts, state, clock, MonthDay.of(12, 1));
        jobs.runRemind1700();       // 不抛异常（触发层兜底）
        jobs.runCalendarCheck();
        assertThat(true).isTrue();
    }

    @Test
    void calendarCheckAlertsOncePerDayFromDecember(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        Clock dec = Clock.fixed(Instant.parse("2026-12-07T01:00:00Z"), ZONE);       // 12 月第一个周一 09:00
        ScheduledJobs jobs = new ScheduledJobs(sync, reminderService(state), admin, alerts, state, dec, MonthDay.of(12, 1));
        admin.config = new AdminConfig(Set.of(), Map.of(), List.of(new Holiday(LocalDate.of(2026, 1, 1), false, "元旦")));
        jobs.runCalendarCheck();
        jobs.runCalendarCheck();                                                    // 同一天只提醒一次
        assertThat(messages.to("ou_ops")).hasSize(1);
        assertThat(messages.to("ou_ops").get(0)[1]).contains("2027");
        // 录入后不再提醒
        messages.sent.clear();
        Clock nextWeek = Clock.fixed(Instant.parse("2026-12-14T01:00:00Z"), ZONE);
        admin.config = new AdminConfig(Set.of(), Map.of(), List.of(new Holiday(LocalDate.of(2027, 1, 1), false, "元旦")));
        new ScheduledJobs(sync, reminderService(state), admin, alerts, state, nextWeek, MonthDay.of(12, 1)).runCalendarCheck();
        assertThat(messages.sent).isEmpty();
        // 11 月不检查
        new ScheduledJobs(sync, reminderService(state), admin, alerts, state, clock, MonthDay.of(12, 1)).runCalendarCheck();
        assertThat(messages.sent).isEmpty();
    }

    CatchUpRunner catchUp(DailyStateStore state, Clock c) {
        ScheduledJobs jobs = new ScheduledJobs(sync, reminderService(state), admin, alerts, state, c, MonthDay.of(12, 1));
        return new CatchUpRunner(jobs, sync, reminderService(state), state, c, LocalTime.of(9, 0), LocalTime.of(16, 50),
                LocalTime.of(17, 0), LocalTime.of(20, 0));
    }

    @Test
    void catchUpBeforeDeadlineSyncsThenReminds(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        Clock c = Clock.fixed(Instant.parse("2026-11-26T10:30:00Z"), ZONE);          // 18:30
        jira.issues = List.of(issue("A-1", "Story", user("a", "甲")));
        catchUp(state, c).runOnStartup();
        assertThat(jira.calls).isEqualTo(1);
        assertThat(messages.to("ou_a")).hasSize(1);
        var runs = state.load(LocalDate.of(2026, 11, 26)).runs();
        assertThat(runs).containsKeys("sync0900", "sync1650", "remind1700");
        catchUp(state, c).runOnStartup();                                            // 再次启动不重复
        assertThat(jira.calls).isEqualTo(1);
    }

    @Test
    void catchUpAfterDeadlineOnlySyncs(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        Clock c = Clock.fixed(Instant.parse("2026-11-26T12:30:00Z"), ZONE);          // 20:30
        jira.issues = List.of(issue("A-1", "Story", user("a", "甲")));
        catchUp(state, c).runOnStartup();
        assertThat(jira.calls).isEqualTo(1);
        assertThat(messages.sent).isEmpty();                                          // 过 20:00 不补发提醒
    }

    @Test
    void catchUpSkipsWeekendsAndEarlyMorning(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        catchUp(state, Clock.fixed(Instant.parse("2026-11-28T10:30:00Z"), ZONE)).runOnStartup();   // 周六
        catchUp(state, Clock.fixed(Instant.parse("2026-11-26T00:30:00Z"), ZONE)).runOnStartup();   // 周四 08:30，尚未到 09:00
        assertThat(jira.calls).isZero();
    }

    @Test
    void periodicCatchUpWaitsForGraceThenCatchesUp(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        jira.issues = List.of(issue("A-1", "Story", user("a", "甲")));
        catchUp(state, Clock.fixed(Instant.parse("2026-11-26T01:02:00Z"), ZONE)).runPeriodic();    // 09:02，未满 3 分钟
        assertThat(jira.calls).isZero();
        catchUp(state, Clock.fixed(Instant.parse("2026-11-26T01:05:00Z"), ZONE)).runPeriodic();    // 09:05，补同步
        assertThat(jira.calls).isEqualTo(1);
        assertThat(state.load(LocalDate.of(2026, 11, 26)).runs()).containsKey("sync0900");
        assertThat(messages.sent).isEmpty();                                                      // 还没到 17:00，不提醒
    }

    @Test
    void periodicCatchUpRemindsAfterMissedSeventeen(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        jira.issues = List.of(issue("A-1", "Story", user("a", "甲")));
        CatchUpRunner runner = catchUp(state, Clock.fixed(Instant.parse("2026-11-26T09:10:00Z"), ZONE));   // 17:10
        runner.runPeriodic();
        assertThat(messages.to("ou_a")).hasSize(1);
        runner.runPeriodic();                                                                     // 已有成功记录，不重复
        assertThat(messages.to("ou_a")).hasSize(1);
        assertThat(jira.calls).isEqualTo(1);
    }

    @Test
    void periodicCatchUpTriesEachPointOnlyOnceEvenIfItFails(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        jira.failures.add(new QdException(ErrorCode.JIRA_AUTH, false, "401"));
        CatchUpRunner runner = catchUp(state, Clock.fixed(Instant.parse("2026-11-26T02:00:00Z"), ZONE));   // 10:00
        runner.runPeriodic();                                                                     // 失败
        runner.runPeriodic();                                                                     // 不再反复重试、不反复通知
        assertThat(jira.calls).isEqualTo(1);
        // 启动补执行不受此限制
        runner.runOnStartup();
        assertThat(jira.calls).isEqualTo(2);
    }

    @Test
    void lateScheduledReminderAfterDeadlineIsNotSent(@TempDir Path dir) {
        DailyStateStore state = new DailyStateStore(dir.resolve("s.json"), mapper);
        jira.issues = List.of(issue("A-1", "Story", user("a", "甲")));
        Clock late = Clock.fixed(Instant.parse("2026-11-26T15:00:00Z"), ZONE);                    // 23:00，定时器因休眠延迟
        ScheduledJobs jobs = new ScheduledJobs(sync, reminderService(state), admin, alerts, state, late, MonthDay.of(12, 1));
        jobs.setRemindDeadline(LocalTime.of(20, 0));
        jobs.runRemind1700();
        assertThat(messages.sent).isEmpty();
    }

    @Test
    void syncAlsoAlignsMirrorTables() {
        jira.issues = List.of(issue("A-1", "Story", user("a", "甲")), issue("A-2", "Story", user("b", "乙")));
        FakeTable mirror = new FakeTable();
        mirror.rows.add(RuleTests.row("OLD-1", com.dreame.qd.model.Models.TaskType.STORY, "ou_a", "甲", "2026-10-01T00:00:00Z").withRecordId("recOld"));
        sync.setMirrors(List.of(mirror));
        SyncResult r = sync.sync(SyncTrigger.MANUAL);
        assertThat(r.success()).isTrue();
        assertThat(table.rows).extracting(TaskRow::key).containsExactlyInAnyOrder("A-1", "A-2");
        assertThat(mirror.rows).extracting(TaskRow::key).containsExactlyInAnyOrder("A-1", "A-2");   // 副本同样对齐，旧行被删除
        assertThat(mirror.deleted).containsExactly("recOld");
    }

    @Test
    void mirrorWriteFailureIsReportedAsSyncFailure() {
        jira.issues = List.of(issue("A-1", "Story", user("a", "甲")));
        FakeTable mirror = new FakeTable() {
            @Override public void createBatch(List<TaskRow> r) { throw new QdException(ErrorCode.FEISHU_FORBIDDEN, false, "无权限"); }
        };
        sync.setMirrors(List.of(mirror));
        SyncResult r = sync.sync(SyncTrigger.MANUAL);
        assertThat(r.success()).isFalse();
        assertThat(messages.to("ou_ops")).isNotEmpty();                                            // 运维收到失败通知
    }
}
