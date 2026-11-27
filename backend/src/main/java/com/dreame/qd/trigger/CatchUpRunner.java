package com.dreame.qd.trigger;

import com.dreame.qd.integration.local.DailyStateStore;
import com.dreame.qd.model.Models.DailyState;
import com.dreame.qd.model.Models.ReminderResult;
import com.dreame.qd.model.Models.SyncResult;
import com.dreame.qd.model.Models.SyncTrigger;
import com.dreame.qd.service.ReminderService;
import com.dreame.qd.service.SyncService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INT-02 启动补执行（02 2.5）：
 * 当天（法定工作日）错过 09:00 / 16:50 同步 → 立即补同步一次；
 * 错过 17:00 提醒且当前时间早于截止时间（默认 20:00）→ 先补同步再补发提醒；已过截止时间或非工作日 → 不补。
 * 每人每天最多 1 条由本地状态文件保证。
 */
public class CatchUpRunner {
    private static final Logger log = LoggerFactory.getLogger(CatchUpRunner.class);

    private final ScheduledJobs jobs;
    private final SyncService syncService;
    private final ReminderService reminderService;
    private final DailyStateStore state;
    private final Clock clock;
    private final LocalTime sync0900;
    private final LocalTime sync1650;
    private final LocalTime remindTime;
    private final LocalTime deadline;

    public CatchUpRunner(ScheduledJobs jobs, SyncService syncService, ReminderService reminderService, DailyStateStore state,
                         Clock clock, LocalTime sync0900, LocalTime sync1650, LocalTime remindTime, LocalTime deadline) {
        this.jobs = jobs;
        this.syncService = syncService;
        this.reminderService = reminderService;
        this.state = state;
        this.clock = clock;
        this.sync0900 = sync0900;
        this.sync1650 = sync1650;
        this.remindTime = remindTime;
        this.deadline = deadline;
    }

    public void runOnStartup() {
        try {
            LocalDate today = LocalDate.now(clock);
            LocalTime now = LocalTime.now(clock);
            if (!jobs.isWorkday(today)) { log.info("补执行：今天不是法定工作日，不补"); return; }
            DailyState s = state.load(today);
            boolean need0900 = !now.isBefore(sync0900) && !s.runs().containsKey(ScheduledJobs.JOB_SYNC_0900);
            boolean need1650 = !now.isBefore(sync1650) && !s.runs().containsKey(ScheduledJobs.JOB_SYNC_1650);
            boolean needRemind = !now.isBefore(remindTime) && now.isBefore(deadline) && !s.runs().containsKey(ScheduledJobs.JOB_REMIND);
            if (!need0900 && !need1650 && !needRemind) { log.info("补执行：当天无错过的定时点"); return; }

            log.info("补执行：同步={}，提醒={}", need0900 || need1650 || needRemind, needRemind);
            SyncResult r = syncService.sync(SyncTrigger.CATCH_UP);
            if (r.success()) {
                if (need0900 || needRemind) state.markRun(today, ScheduledJobs.JOB_SYNC_0900, clock.instant());
                if (need1650 || needRemind) state.markRun(today, ScheduledJobs.JOB_SYNC_1650, clock.instant());
            }
            if (needRemind) {
                ReminderResult rr = reminderService.sendDailyReminders(today, true);
                if (rr.success()) state.markRun(today, ScheduledJobs.JOB_REMIND, clock.instant());
            }
        } catch (Throwable t) {
            log.error("补执行异常：{}", t.toString(), t);
        }
    }
}
