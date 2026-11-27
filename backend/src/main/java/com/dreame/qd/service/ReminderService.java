package com.dreame.qd.service;

import com.dreame.qd.integration.feishu.AdminConfigRepository;
import com.dreame.qd.integration.feishu.CardBuilder;
import com.dreame.qd.integration.feishu.ContactGateway;
import com.dreame.qd.integration.feishu.MessageGateway;
import com.dreame.qd.integration.feishu.TaskTableGateway;
import com.dreame.qd.integration.jira.JiraClient;
import com.dreame.qd.integration.local.DailyStateStore;
import com.dreame.qd.model.ErrorCode;
import com.dreame.qd.model.Models.AdminConfig;
import com.dreame.qd.model.Models.JiraUser;
import com.dreame.qd.model.Models.RecipientResolution;
import com.dreame.qd.model.Models.ReminderMessage;
import com.dreame.qd.model.Models.ReminderResult;
import com.dreame.qd.model.Models.ReminderTarget;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.Models.Undelivered;
import com.dreame.qd.model.QdException;
import com.dreame.qd.rule.AccountMapper;
import com.dreame.qd.rule.RecipientResolver;
import com.dreame.qd.rule.ReminderComposer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INT-11 发送每日提醒：读取主表格与管理表格 → 识别提醒对象 → 逐人（当天已发过则跳过）组装并私聊发送（出错重试 1 次）
 * → 汇总未送达并通知。单人失败不中断整轮；每发送成功一人立即写入本地状态，重启后不会重复发送。
 */
public class ReminderService {
    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final TaskTableGateway table;
    private final AdminConfigRepository adminConfig;
    private final ContactGateway contacts;
    private final JiraClient jira;
    private final ReminderComposer composer;
    private final CardBuilder cards;
    private final MessageGateway messages;
    private final DailyStateStore state;
    private final RetryExecutor retry;
    private final AlertService alerts;
    private final Clock clock;

    public ReminderService(TaskTableGateway table, AdminConfigRepository adminConfig, ContactGateway contacts, JiraClient jira,
                           ReminderComposer composer, CardBuilder cards, MessageGateway messages, DailyStateStore state,
                           RetryExecutor retry, AlertService alerts, Clock clock) {
        this.table = table;
        this.adminConfig = adminConfig;
        this.contacts = contacts;
        this.jira = jira;
        this.composer = composer;
        this.cards = cards;
        this.messages = messages;
        this.state = state;
        this.retry = retry;
        this.alerts = alerts;
        this.clock = clock;
    }

    private java.util.Set<String> onlyOpenIds = java.util.Set.of();

    /** 联调用：非空时只向这些 open_id 发送提醒。 */
    public void setOnlyOpenIds(java.util.Collection<String> ids) { this.onlyOpenIds = java.util.Set.copyOf(ids); }

    public ReminderResult sendDailyReminders(LocalDate date, boolean catchUp) {
        long t0 = System.nanoTime();
        List<TaskRow> rows;
        AdminConfig config;
        RecipientResolution resolution;
        try {
            var rowsOutcome = retry.run(table::listAll);
            if (!rowsOutcome.success()) throw rowsOutcome.failure();
            rows = rowsOutcome.value();
            var cfgOutcome = retry.run(adminConfig::load);
            if (!cfgOutcome.success()) throw cfgOutcome.failure();
            config = cfgOutcome.value();
            resolution = resolveRecipients(rows, config);
        } catch (QdException e) {
            log.error("17:00 提醒无法准备数据：{}", e.summary());
            alerts.custom("提醒发送失败", "17:00 提醒因无法读取表格数据而未发送。\n原因：" + e.summary() + "\n请处理后在飞书中手动联系相关人员，或等待补发。");
            return new ReminderResult(false, e.errorCode().code(), e.getMessage(), clock.instant(), 0, 0, 0, List.of());
        }

        Instant dataAsOf = rows.stream().map(TaskRow::syncedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        List<Undelivered> undelivered = new ArrayList<>(resolution.undelivered());
        int sent = 0, skipped = 0, retried = 0;
        for (ReminderTarget target : resolution.targets()) {
            if (!onlyOpenIds.isEmpty() && !onlyOpenIds.contains(target.openId())) continue;
            if (state.isSent(date, target.openId())) { skipped++; continue; }
            ReminderMessage msg = composer.compose(target, dataAsOf);
            String card = cards.reminder(msg);
            String uuid = UUID.nameUUIDFromBytes((date + "|" + target.openId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            var outcome = retry.run(() -> { messages.sendCard(target.openId(), card, uuid); return Boolean.TRUE; });
            if (outcome.success()) {
                state.markSent(date, target.openId());
                sent++;
                if (outcome.retried() > 0) retried++;
            } else {
                log.warn("提醒发送失败：{} {}", target.displayName(), outcome.failure().summary());
                undelivered.add(new Undelivered(target.displayName(), outcome.failure().retryable()
                        ? "发送出错（已自动重试 1 次仍失败）：" + outcome.failure().getMessage() : outcome.failure().getMessage()));
            }
        }
        alerts.undelivered(date, undelivered);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("17:00 提醒完成（补发={}）：发送 {}，已发过跳过 {}，重试成功 {}，未送达 {}，耗时 {} ms",
                catchUp, sent, skipped, retried, undelivered.size(), ms);
        return new ReminderResult(true, "OK", "已发送 " + sent + " 人，未送达 " + undelivered.size() + " 人", clock.instant(),
                sent, skipped, retried, undelivered);
    }

    /** 把排除名单（Jira 账号）对应为 open_id / 未对应显示名后识别提醒对象。 */
    RecipientResolution resolveRecipients(List<TaskRow> rows, AdminConfig config) {
        Set<String> excludedOpenIds = new HashSet<>();
        Set<String> excludedDisplays = new HashSet<>();
        Map<String, JiraUser> needLookup = new java.util.LinkedHashMap<>();
        for (String acc : config.excludes()) {
            String openId = config.mappings().get(acc);
            if (openId != null) { excludedOpenIds.add(openId); continue; }
            try {
                jira.findUser(acc).ifPresent(u -> needLookup.put(acc, u));
            } catch (QdException e) {
                log.warn("排除名单账号 {} 无法查询 Jira 用户：{}", acc, e.summary());
            }
        }
        if (!needLookup.isEmpty()) {
            Map<String, String> byEmail = contacts.findOpenIdsByEmail(needLookup.values().stream().map(JiraUser::email).filter(Objects::nonNull).toList());
            needLookup.forEach((acc, u) -> {
                String openId = byEmail.get(AccountMapper.normalize(u.email()));
                if (openId != null) excludedOpenIds.add(openId);
                else excludedDisplays.add((u.displayName() == null || u.displayName().isBlank() ? u.account() : u.displayName()) + "（未对应）");
            });
        }
        return RecipientResolver.resolve(rows, excludedOpenIds, excludedDisplays);
    }
}
