package com.dreame.qd.trigger;

import com.dreame.qd.integration.feishu.AdminConfigRepository;
import com.dreame.qd.integration.local.DailyStateStore;
import com.dreame.qd.model.Models.Holiday;
import com.dreame.qd.model.Models.ReminderResult;
import com.dreame.qd.model.Models.SyncResult;
import com.dreame.qd.model.Models.SyncTrigger;
import com.dreame.qd.model.QdException;
import com.dreame.qd.rule.WorkdayCalendar;
import com.dreame.qd.service.AlertService;
import com.dreame.qd.service.ReminderService;
import com.dreame.qd.service.SyncService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INT-01 定时任务入口。非工作日直接返回（INT-03）；所有异常在此兜底，保证单次失败不影响后续定时任务（04 1.5）。
 */
public class ScheduledJobs {
    public static final String JOB_SYNC_0900 = "sync0900";
    public static final String JOB_SYNC_1650 = "sync1650";
    public static final String JOB_REMIND = "remind1700";
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final SyncService syncService;
    private final ReminderService reminderService;
    private final AdminConfigRepository adminConfig;
    private final AlertService alerts;
    private final DailyStateStore state;
    private final Clock clock;
    private final MonthDay calendarCheckStart;

    public ScheduledJobs(SyncService syncService, ReminderService reminderService, AdminConfigRepository adminConfig,
                         AlertService alerts, DailyStateStore state, Clock clock, MonthDay calendarCheckStart) {
        this.syncService = syncService;
        this.reminderService = reminderService;
        this.adminConfig = adminConfig;
        this.alerts = alerts;
        this.state = state;
        this.clock = clock;
        this.calendarCheckStart = calendarCheckStart;
    }

    public void runSync0900() { runSync(SyncTrigger.SCHEDULED_0900, JOB_SYNC_0900); }

    public void runSync1650() { runSync(SyncTrigger.SCHEDULED_1650, JOB_SYNC_1650); }

    private void runSync(SyncTrigger trigger, String job) {
        try {
            LocalDate today = today();
            if (!isWorkday(today)) { log.info("{}：今天不是法定工作日，跳过", trigger.label()); return; }
            SyncResult r = syncService.sync(trigger);
            if (r.success()) state.markRun(today, job, clock.instant());
        } catch (Throwable t) {
            log.error("{}异常：{}", trigger.label(), t.toString(), t);
        }
    }

    public void runRemind1700() {
        try {
            LocalDate today = today();
            if (!isWorkday(today)) { log.info("17:00 提醒：今天不是法定工作日，跳过"); return; }
            ReminderResult r = reminderService.sendDailyReminders(today, false);
            if (r.success()) state.markRun(today, JOB_REMIND, clock.instant());
        } catch (Throwable t) {
            log.error("17:00 提醒异常：{}", t.toString(), t);
        }
    }

    /** 每周一 09:00：12 月 1 日起，次年日历未录入则提醒运维与备份；同一天只提醒一次。 */
    public void runCalendarCheck() {
        try {
            LocalDate today = today();
            if (MonthDay.from(today).isBefore(calendarCheckStart)) return;
            if (today.equals(state.load(today).lastCalendarCheck())) return;
            List<Holiday> holidays = adminConfig.load().holidays();
            int next = today.getYear() + 1;
            if (!WorkdayCalendar.isYearEntered(next, holidays)) {
                alerts.calendarMissing(next);
                state.markCalendarChecked(today);
            }
        } catch (Throwable t) {
            log.error("年底日历检查异常：{}", t.toString(), t);
        }
    }

    /** 工作日判断；管理表格读取失败时退化为"周一至周五"并写日志，避免因配置读取失败而漏同步。 */
    boolean isWorkday(LocalDate day) {
        try {
            return WorkdayCalendar.isWorkday(day, adminConfig.load().holidays());
        } catch (QdException e) {
            log.warn("读取节假日日历失败，按周一至周五判断：{}", e.summary());
            return WorkdayCalendar.isWorkday(day, List.of());
        }
    }

    private LocalDate today() { return LocalDate.now(clock); }
}
