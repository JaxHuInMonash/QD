package com.dreame.qd.config;

import com.dreame.qd.integration.feishu.AdminConfigRepository;
import com.dreame.qd.integration.feishu.BitableClient;
import com.dreame.qd.integration.feishu.BitableTaskTableGateway;
import com.dreame.qd.integration.feishu.CardBuilder;
import com.dreame.qd.integration.feishu.ContactGateway;
import com.dreame.qd.integration.feishu.FeishuContactGateway;
import com.dreame.qd.integration.feishu.FeishuHttp;
import com.dreame.qd.integration.feishu.FeishuMessageGateway;
import com.dreame.qd.integration.feishu.MessageGateway;
import com.dreame.qd.integration.feishu.TaskTableGateway;
import com.dreame.qd.integration.jira.HttpJiraClient;
import com.dreame.qd.integration.jira.JiraClient;
import com.dreame.qd.integration.jira.RateLimiter;
import com.dreame.qd.integration.local.DailyStateStore;
import com.dreame.qd.rule.ReminderComposer;
import com.dreame.qd.rule.TaskMapper;
import com.dreame.qd.service.AlertService;
import com.dreame.qd.service.ReminderService;
import com.dreame.qd.service.RetryExecutor;
import com.dreame.qd.service.SyncService;
import com.dreame.qd.trigger.CatchUpRunner;
import com.dreame.qd.trigger.LongConnectionListener;
import com.dreame.qd.trigger.ManualSyncHandler;
import com.dreame.qd.trigger.ScheduledJobs;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** 装配所有组件。构造时先校验配置：有错误则拒绝启动（04 1.4、Q4），并尽力通知运维负责人与备份负责人。 */
@Configuration
@EnableConfigurationProperties(QdProperties.class)
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final QdProperties props;
    private final ZoneId zone;

    public AppConfig(QdProperties props) {
        this.props = props;
        var errors = ConfigValidator.validate(props);
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("QD-CONFIG-001 配置有误，拒绝启动：");
            errors.forEach(e -> sb.append("\n  · ").append(e));
            sb.append("\n修改方法：编辑 application.yml / config 目录下的覆盖配置，或设置对应的环境变量后重启。");
            log.error(sb.toString());
            notifyConfigErrors(errors);
            throw new IllegalStateException(sb.toString());
        }
        this.zone = ZoneId.of(props.getTimezone());
    }

    @Bean Clock clock() { return Clock.system(zone); }

    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }

    @Bean TaskMapper taskMapper() { return new TaskMapper(props.getJira().getIssueTypes(), props.getJira().getBaseUrl()); }

    @Bean RetryExecutor retryExecutor() { return new RetryExecutor(props.getHttp().getRetryInterval()); }

    @Bean JiraClient jiraClient(ObjectMapper mapper) {
        return new HttpJiraClient(props, mapper, new RateLimiter(props.getJira().getMaxRequestsPerMinute(), 60_000));
    }

    @Bean FeishuHttp feishuHttp(ObjectMapper mapper) { return new FeishuHttp(props, mapper); }

    @Bean BitableClient bitableClient(FeishuHttp http) { return new BitableClient(http); }

    @Bean TaskTableGateway taskTableGateway(BitableClient c, TaskMapper m) { return new BitableTaskTableGateway(c, props, m); }

    @Bean AdminConfigRepository adminConfigRepository(BitableClient c) { return new AdminConfigRepository(c, props); }

    @Bean ContactGateway contactGateway(FeishuHttp http) { return new FeishuContactGateway(http); }

    @Bean MessageGateway messageGateway(FeishuHttp http) { return new FeishuMessageGateway(http); }

    @Bean CardBuilder cardBuilder(ObjectMapper mapper) { return new CardBuilder(mapper, zone); }

    @Bean DailyStateStore dailyStateStore(ObjectMapper mapper) { return new DailyStateStore(Path.of(props.getStorage().getStateFile()), mapper); }

    @Bean ReminderComposer reminderComposer(TaskMapper m) { return new ReminderComposer(m, props.getFeishu().getMain().getViewMineUrl()); }

    @Bean AlertService alertService(MessageGateway g, CardBuilder c, Clock clock) { return new AlertService(g, c, props, clock); }

    @Bean SyncService syncService(JiraClient jira, ContactGateway contacts, AdminConfigRepository admin, TaskTableGateway table,
                                  TaskMapper tm, RetryExecutor retry, AlertService alerts, Clock clock) {
        return new SyncService(jira, contacts, admin, table, tm, retry, alerts, clock);
    }

    @Bean ReminderService reminderService(TaskTableGateway table, AdminConfigRepository admin, ContactGateway contacts, JiraClient jira,
                                          ReminderComposer composer, CardBuilder cards, MessageGateway messages, DailyStateStore state,
                                          RetryExecutor retry, AlertService alerts, Clock clock) {
        ReminderService svc = new ReminderService(table, admin, contacts, jira, composer, cards, messages, state, retry, alerts, clock);
        svc.setOnlyOpenIds(props.getReminderTestOpenIds());
        if (!props.getReminderTestOpenIds().isEmpty()) log.warn("联调模式：17:00 提醒仅发送给 {} 人", props.getReminderTestOpenIds().size());
        return svc;
    }

    @Bean ScheduledJobs scheduledJobs(SyncService sync, ReminderService reminder, AdminConfigRepository admin, AlertService alerts,
                                      DailyStateStore state, Clock clock) {
        return new ScheduledJobs(sync, reminder, admin, alerts, state, clock, ConfigValidator.parseMonthDay(props.getSchedule().getCalendarCheckStart()));
    }

    @Bean CatchUpRunner catchUpRunner(ScheduledJobs jobs, SyncService sync, ReminderService reminder, DailyStateStore state, Clock clock) {
        var s = props.getSchedule();
        return new CatchUpRunner(jobs, sync, reminder, state, clock, ConfigValidator.parseTime(s.getSyncTimes().get(0)),
                ConfigValidator.parseTime(s.getSyncTimes().get(1)), ConfigValidator.parseTime(s.getRemindTime()),
                ConfigValidator.parseTime(s.getRemindCatchupDeadline()));
    }

    @Bean ManualSyncHandler manualSyncHandler(SyncService sync, ObjectMapper mapper) { return new ManualSyncHandler(sync, mapper); }

    @Bean(destroyMethod = "stop") LongConnectionListener longConnectionListener(ManualSyncHandler handler) {
        return new LongConnectionListener(props.getFeishu().getAppId(), props.getFeishu().getAppSecret(), handler);
    }

    @Bean ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("qd-job-");
        return s;
    }

    /** 启动顺序：建立长连接 → 注册定时点（北京时间）→ 后台补执行当天错过的定时点。 */
    @Bean ApplicationRunner starter(LongConnectionListener listener, ThreadPoolTaskScheduler scheduler, ScheduledJobs jobs, CatchUpRunner catchUp) {
        return (ApplicationArguments args) -> {
            listener.start();
            var s = props.getSchedule();
            scheduler.schedule(jobs::runSync0900, cron(s.getSyncTimes().get(0), "*"));
            scheduler.schedule(jobs::runSync1650, cron(s.getSyncTimes().get(1), "*"));
            scheduler.schedule(jobs::runRemind1700, cron(s.getRemindTime(), "*"));
            String[] cal = ConfigValidator.parseCalendarCheck(s.getCalendarCheck());
            scheduler.schedule(jobs::runCalendarCheck, cron(cal[1], cal[0]));
            scheduler.execute(catchUp::runOnStartup);
            log.info("QD助手已启动：同步 {}，提醒 {}，补发截止 {}", s.getSyncTimes(), s.getRemindTime(), s.getRemindCatchupDeadline());
        };
    }

    private CronTrigger cron(String hhmm, String dayOfWeek) {
        LocalTime t = LocalTime.parse(hhmm);
        return new CronTrigger("0 " + t.getMinute() + " " + t.getHour() + " * * " + dayOfWeek, zone);
    }

    /** 飞书相关配置本身正确时，把配置错误同时私聊运维与备份负责人（尽力而为，失败只写日志）。 */
    private void notifyConfigErrors(java.util.List<String> errors) {
        var f = props.getFeishu();
        boolean canSend = ok(f.getAppId()) && ok(f.getAppSecret()) && !f.getOpsOpenIds().isEmpty()
                && f.getOpsOpenIds().stream().noneMatch(s -> s == null || s.isBlank() || s.startsWith("<"));
        if (!canSend) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            CardBuilder cards = new CardBuilder(mapper, zone);
            AlertService alerts = new AlertService(new FeishuMessageGateway(new FeishuHttp(props, mapper)), cards, props, Clock.system(zone));
            alerts.custom("QD助手启动失败", "QD-CONFIG-001 配置有误，服务未启动：\n" + String.join("\n", errors.stream().map(e -> "· " + e).toList()));
        } catch (RuntimeException e) {
            log.error("配置错误通知发送失败：{}", e.getMessage());
        }
    }

    private static boolean ok(String s) { return s != null && !s.isBlank() && !s.startsWith("<"); }
}
