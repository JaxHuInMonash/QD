package com.dreame.qd;

import static org.assertj.core.api.Assertions.assertThat;

import com.dreame.qd.config.ConfigValidator;
import com.dreame.qd.config.QdProperties;
import com.dreame.qd.integration.jira.RateLimiter;
import com.dreame.qd.integration.local.DailyStateStore;
import com.dreame.qd.model.ErrorCode;
import com.dreame.qd.model.QdException;
import com.dreame.qd.service.RetryExecutor;
import com.dreame.qd.trigger.SyncRequestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupportTests {
    final ObjectMapper mapper = new ObjectMapper();

    // ---------- RetryExecutor（04 1.6） ----------
    @Test
    void retriesOnlyRetryableErrorsOnce() {
        RetryExecutor retry = new RetryExecutor(Duration.ZERO, ms -> { });
        AtomicInteger calls = new AtomicInteger();
        var ok = retry.run(() -> { if (calls.incrementAndGet() == 1) throw new QdException(ErrorCode.JIRA_SERVER, true, "5xx"); return "ok"; });
        assertThat(ok.success()).isTrue();
        assertThat(ok.retried()).isEqualTo(1);

        calls.set(0);
        var twice = retry.run(() -> { calls.incrementAndGet(); throw new QdException(ErrorCode.JIRA_TIMEOUT, true, "timeout"); });
        assertThat(twice.success()).isFalse();
        assertThat(calls.get()).isEqualTo(2);       // 只重试 1 次
        assertThat(twice.retried()).isEqualTo(1);

        calls.set(0);
        var auth = retry.run(() -> { calls.incrementAndGet(); throw new QdException(ErrorCode.JIRA_AUTH, false, "401"); });
        assertThat(auth.success()).isFalse();
        assertThat(calls.get()).isEqualTo(1);       // 认证失败不重试
        assertThat(auth.retried()).isZero();
    }

    // ---------- RateLimiter ----------
    @Test
    void rateLimiterBlocksBeyondMaxPerWindow() {
        AtomicLong now = new AtomicLong(0);
        List<Long> sleeps = new ArrayList<>();
        RateLimiter l = new RateLimiter(3, 60_000, now::get, ms -> { sleeps.add(ms); now.addAndGet(ms); });
        for (int i = 0; i < 3; i++) l.acquire();
        assertThat(sleeps).isEmpty();
        l.acquire();                                   // 第 4 个需等到窗口滑出
        assertThat(sleeps).containsExactly(60_000L);
    }

    // ---------- EVT-01 解析（V3 实测卡片格式） ----------
    @Test
    void parsesInteractiveCardFromWorkflow() throws Exception {
        String dsl = "{\"body\":{\"elements\":[{\"content\":\"#QD立即同步\\n点击人：{@点击人}\",\"tag\":\"markdown\"},{\"tag\":\"hr\"},{\"content\":\"\",\"tag\":\"markdown\"}]}}";
        String content = mapper.writeValueAsString(java.util.Map.of("title", "测试按钮和工作流", "user_dsl", dsl));
        String text = SyncRequestParser.extractText(mapper, "interactive", content);
        assertThat(text).startsWith("#QD立即同步");
        assertThat(SyncRequestParser.isSyncRequest(text)).isTrue();
    }

    @Test
    void parsesPlainTextAndIgnoresOthers() {
        assertThat(SyncRequestParser.isSyncRequest(SyncRequestParser.extractText(mapper, "text", "{\"text\":\"#QD立即同步 测试\"}"))).isTrue();
        assertThat(SyncRequestParser.isSyncRequest(SyncRequestParser.extractText(mapper, "text", "{\"text\":\"大家好\"}"))).isFalse();
        assertThat(SyncRequestParser.isSyncRequest(SyncRequestParser.extractText(mapper, "interactive", "{\"user_dsl\":\"不是JSON\"}"))).isFalse();
        assertThat(SyncRequestParser.isSyncRequest(null)).isFalse();
    }

    // ---------- DailyStateStore（INT-13） ----------
    @Test
    void stateStorePersistsAndResetsOnNewDay(@TempDir Path dir) {
        Path file = dir.resolve("data/reminder-state.json");
        LocalDate d1 = LocalDate.of(2026, 11, 26);
        DailyStateStore s = new DailyStateStore(file, mapper);
        assertThat(s.isSent(d1, "ou_a")).isFalse();
        s.markSent(d1, "ou_a");
        s.markRun(d1, "sync0900", Instant.parse("2026-11-26T01:00:04Z"));
        s.markCalendarChecked(d1);

        DailyStateStore reopened = new DailyStateStore(file, mapper);            // 重启后仍记得
        assertThat(reopened.isSent(d1, "ou_a")).isTrue();
        assertThat(reopened.load(d1).runs()).containsKey("sync0900");
        var next = reopened.load(d1.plusDays(1));                                // 跨天：只保留当天
        assertThat(next.sentOpenIds()).isEmpty();
        assertThat(next.runs()).isEmpty();
        assertThat(next.lastCalendarCheck()).isEqualTo(d1);                      // 年底日历检查日期保留
    }

    @Test
    void corruptStateFileIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        Files.writeString(file, "{坏掉的内容");
        assertThat(new DailyStateStore(file, mapper).isSent(LocalDate.of(2026, 11, 26), "ou_a")).isFalse();
    }

    // ---------- ConfigValidator（Q4：拒绝启动） ----------
    @Test
    void validatorListsEveryProblem() {
        QdProperties p = new QdProperties();
        List<String> errors = ConfigValidator.validate(p);
        assertThat(errors).anyMatch(e -> e.contains("QD_JIRA_PASSWORD"));
        assertThat(errors).anyMatch(e -> e.contains("QD_FEISHU_APP_SECRET"));
        assertThat(errors).anyMatch(e -> e.contains("main.app-token"));
        assertThat(errors).anyMatch(e -> e.contains("ops-open-ids"));
        assertThat(errors).anyMatch(e -> e.contains("issue-types"));
    }

    @Test
    void validatorAcceptsCompleteConfigAndRejectsPlaceholders() {
        QdProperties p = complete();
        assertThat(ConfigValidator.validate(p)).isEmpty();
        p.getFeishu().getMain().setAppToken("<主表格 app_token>");
        assertThat(ConfigValidator.validate(p)).anyMatch(e -> e.contains("main.app-token"));
        p = complete();
        p.getSchedule().setRemindCatchupDeadline("16:00");
        assertThat(ConfigValidator.validate(p)).anyMatch(e -> e.contains("remind-catchup-deadline"));
    }

    static QdProperties complete() {
        QdProperties p = new QdProperties();
        p.getJira().setBaseUrl("https://jira.example.com");
        p.getJira().setBoardJql("project = X");
        p.getJira().setUsername("u");
        p.getJira().setPassword("p");
        p.getJira().getIssueTypes().put("Story", List.of("Story"));
        p.getFeishu().setAppId("cli_x");
        p.getFeishu().setAppSecret("s");
        p.getFeishu().getMain().setAppToken("a");
        p.getFeishu().getMain().setTaskTableId("t");
        p.getFeishu().getMain().setViewMineUrl("https://v");
        p.getFeishu().getMain().setViewAllUrl("https://v2");
        p.getFeishu().getAdmin().setAppToken("a2");
        p.getFeishu().getAdmin().setExcludeTableId("t1");
        p.getFeishu().getAdmin().setMappingTableId("t2");
        p.getFeishu().getAdmin().setHolidayTableId("t3");
        p.getFeishu().setOpsOpenIds(List.of("ou_ops"));
        p.getFeishu().setBackupOpenIds(List.of("ou_backup"));
        return p;
    }
}
