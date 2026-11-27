package com.dreame.qd.config;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 启动时配置校验（04 1.4、Q4）：任何错误都拒绝启动，并列出每一项错误与修改方法。 */
public final class ConfigValidator {
    private static final Pattern CALENDAR_CHECK = Pattern.compile("^(MON|TUE|WED|THU|FRI|SAT|SUN) (\\d{2}:\\d{2})$");

    private ConfigValidator() {}

    public static List<String> validate(QdProperties p) {
        List<String> e = new ArrayList<>();
        try { ZoneId.of(p.getTimezone()); } catch (DateTimeException ex) { e.add("qd.timezone 不是合法时区：" + p.getTimezone()); }

        QdProperties.Jira j = p.getJira();
        need(e, j.getBaseUrl(), "qd.jira.base-url");
        need(e, j.getBoardJql(), "qd.jira.board-jql");
        need(e, j.getUsername(), "环境变量 QD_JIRA_USERNAME（Jira 登录名）");
        need(e, j.getPassword(), "环境变量 QD_JIRA_PASSWORD（Jira 密码）");
        if (j.getIssueTypes().isEmpty()) e.add("qd.jira.issue-types 不能为空（Story / Task / Bug 对应的 Jira 类型名）");
        if (j.getPageSize() < 1 || j.getPageSize() > 1000) e.add("qd.jira.page-size 应在 1～1000 之间");
        if (j.getMaxRequestsPerMinute() < 1 || j.getMaxRequestsPerMinute() > 100) e.add("qd.jira.max-requests-per-minute 应在 1～100 之间（网关上限 100）");

        QdProperties.Feishu f = p.getFeishu();
        need(e, f.getAppId(), "qd.feishu.app-id");
        need(e, f.getAppSecret(), "环境变量 QD_FEISHU_APP_SECRET（飞书应用 App Secret）");
        need(e, f.getMain().getAppToken(), "qd.feishu.main.app-token（主表格 app_token）");
        need(e, f.getMain().getTaskTableId(), "qd.feishu.main.task-table-id（未完成任务 table_id）");
        need(e, f.getMain().getViewMineUrl(), "qd.feishu.main.view-mine-url（我的未完成 视图链接）");
        need(e, f.getAdmin().getAppToken(), "qd.feishu.admin.app-token（管理表格 app_token）");
        need(e, f.getAdmin().getExcludeTableId(), "qd.feishu.admin.exclude-table-id");
        need(e, f.getAdmin().getMappingTableId(), "qd.feishu.admin.mapping-table-id");
        need(e, f.getAdmin().getHolidayTableId(), "qd.feishu.admin.holiday-table-id");
        needList(e, f.getOpsOpenIds(), "qd.feishu.ops-open-ids（运维负责人 open_id，至少 1 个）");
        needList(e, f.getBackupOpenIds(), "qd.feishu.backup-open-ids（备份负责人 open_id，至少 1 个）");

        List<String> sync = p.getSchedule().getSyncTimes();
        if (sync == null || sync.size() != 2) e.add("qd.schedule.sync-times 必须配置 2 个时间（如 [\"09:00\", \"16:50\"]）");
        else sync.forEach(t -> time(e, t, "qd.schedule.sync-times"));
        LocalTime remind = time(e, p.getSchedule().getRemindTime(), "qd.schedule.remind-time");
        LocalTime deadline = time(e, p.getSchedule().getRemindCatchupDeadline(), "qd.schedule.remind-catchup-deadline");
        if (remind != null && deadline != null && !deadline.isAfter(remind)) e.add("qd.schedule.remind-catchup-deadline 必须晚于 remind-time");
        if (p.getSchedule().getCalendarCheck() == null || !CALENDAR_CHECK.matcher(p.getSchedule().getCalendarCheck()).matches()) {
            e.add("qd.schedule.calendar-check 格式应为 \"MON 09:00\"");
        }
        try { parseMonthDay(p.getSchedule().getCalendarCheckStart()); } catch (RuntimeException ex) { e.add("qd.schedule.calendar-check-start 格式应为 \"12-01\""); }

        need(e, p.getStorage().getStateFile(), "qd.storage.state-file");
        return e;
    }

    public static LocalTime parseTime(String s) { return LocalTime.parse(s); }

    public static MonthDay parseMonthDay(String s) { return MonthDay.parse("--" + s); }

    /** @return 星期缩写与时间，如 ["MON", "09:00"] */
    public static String[] parseCalendarCheck(String s) {
        Matcher m = CALENDAR_CHECK.matcher(s);
        if (!m.matches()) throw new IllegalArgumentException(s);
        return new String[] {m.group(1), m.group(2)};
    }

    private static LocalTime time(List<String> errors, String v, String name) {
        try {
            return LocalTime.parse(v);
        } catch (RuntimeException ex) {
            errors.add(name + " 不是合法的 HH:mm 时间：" + v);
            return null;
        }
    }

    private static void need(List<String> errors, String v, String what) {
        if (v == null || v.isBlank() || v.startsWith("<")) errors.add("缺少配置：" + what);
    }

    private static void needList(List<String> errors, List<String> v, String what) {
        if (v == null || v.isEmpty() || v.stream().anyMatch(s -> s == null || s.isBlank() || s.startsWith("<"))) errors.add("缺少配置：" + what);
    }
}
