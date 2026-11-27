package com.dreame.qd.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 跨层业务对象（03 第 8 章）。 */
public final class Models {
    private Models() {}

    /** 表格类型。 */
    public enum TaskType {
        STORY("Story"), TASK("Task"), BUG("Bug");
        private final String label;
        TaskType(String label) { this.label = label; }
        public String label() { return label; }
        public static TaskType fromLabel(String s) {
            for (TaskType t : values()) if (t.label.equals(s)) return t;
            return null;
        }
    }

    /** 状态类别（"完成"类不会进入主表格）。 */
    public enum StatusCategory {
        TODO("待办"), IN_PROGRESS("进行中");
        private final String label;
        StatusCategory(String label) { this.label = label; }
        public String label() { return label; }
        public static StatusCategory fromLabel(String s) {
            for (StatusCategory c : values()) if (c.label.equals(s)) return c;
            return null;
        }
    }

    public enum SyncTrigger {
        SCHEDULED_0900("09:00 定时同步"), SCHEDULED_1650("16:50 定时同步"), MANUAL("手动同步"), CATCH_UP("补执行同步");
        private final String label;
        SyncTrigger(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record JiraUser(String account, String displayName, String email) {}

    public record JiraIssue(String key, String summary, String typeName, String projectKey,
                            JiraUser assignee, JiraUser reporter, String statusName,
                            String statusCategoryKey, String priority, Instant created, Instant updated) {}

    /** 账号对应结果。displayText 如 "孙伟（未对应）"。 */
    public record PersonRef(JiraUser jiraUser, String feishuOpenId, String displayText, boolean mapped) {}

    /** 主表格中的一行（03 2.1）。recordId 仅后端内部使用，新建时为 null。 */
    public record TaskRow(String recordId, String key, String title, TaskType type, String project,
                          String assigneeOpenId, String assigneeDisplay, String reporter, String status,
                          StatusCategory category, String priority, Instant created, Instant updated,
                          Instant syncedAt) {
        public TaskRow withRecordId(String id) {
            return new TaskRow(id, key, title, type, project, assigneeOpenId, assigneeDisplay, reporter,
                    status, category, priority, created, updated, syncedAt);
        }
    }

    public record ReminderTarget(String openId, String displayName, List<TaskRow> tasks) {}

    public record Undelivered(String person, String reason) {}

    public record RecipientResolution(List<ReminderTarget> targets, List<Undelivered> undelivered) {}

    public record Holiday(LocalDate date, boolean makeup, String name) {}

    /** 管理表格读取结果。mappings：Jira 账号（小写）→ 飞书 open_id。 */
    public record AdminConfig(Set<String> excludes, Map<String, String> mappings, List<Holiday> holidays) {}

    public record DailyState(LocalDate date, Map<String, Instant> runs, Set<String> sentOpenIds,
                             LocalDate lastCalendarCheck) {
        public static DailyState empty(LocalDate date) {
            return new DailyState(date, new java.util.HashMap<>(), new java.util.HashSet<>(), null);
        }
    }

    public record SyncResult(SyncTrigger trigger, boolean success, String code, int count, int retried,
                             String message, Instant finishedAt) {}

    public record ReminderItem(String key, String url, String title, String status) {}

    public record ReminderSection(TaskType type, int total, List<ReminderItem> items) {}

    public record ReminderMessage(String receiverOpenId, Instant dataAsOf, List<ReminderSection> sections,
                                  String viewAllUrl) {}

    public record ReminderResult(boolean success, String code, String message, Instant finishedAt,
                                 int sentCount, int skippedCount, int retriedCount, List<Undelivered> undelivered) {}
}
