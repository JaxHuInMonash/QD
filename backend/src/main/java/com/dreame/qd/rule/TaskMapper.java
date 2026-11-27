package com.dreame.qd.rule;

import com.dreame.qd.model.Models.JiraComment;
import com.dreame.qd.model.Models.JiraIssue;
import com.dreame.qd.model.Models.PersonRef;
import com.dreame.qd.model.Models.StatusCategory;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.Models.TaskType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Jira Issue → 主表格行（03 4.2）；同时执行"只保留未完成、只保留三种类型"的二次校验（R2）。 */
public final class TaskMapper {
    public static final String UNASSIGNED = "未分配";
    /** 单元格长度保护（飞书文本字段有上限，实测看板内最长评论合计约 1.8 万字）。 */
    public static final int MAX_DESCRIPTION = 20_000;
    public static final int MAX_COMMENTS = 50_000;
    static final int MAX_ONE_COMMENT = 10_000;
    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.of("Asia/Shanghai"));

    private final Map<String, TaskType> typeByJiraName = new java.util.HashMap<>();
    private final String jiraBaseUrl;

    /** @param issueTypes 表格类型标签（Story/Task/Bug）→ Jira 类型名列表 */
    public TaskMapper(Map<String, List<String>> issueTypes, String jiraBaseUrl) {
        issueTypes.forEach((label, names) -> {
            TaskType t = TaskType.fromLabel(label);
            if (t != null) names.forEach(n -> typeByJiraName.put(n, t));
        });
        this.jiraBaseUrl = jiraBaseUrl == null ? "" : jiraBaseUrl.replaceAll("/+$", "");
    }

    public String browseUrl(String key) { return jiraBaseUrl + "/browse/" + key; }

    /** 类型不在范围、或状态类别为"完成"时返回空。 */
    public Optional<TaskRow> toRow(JiraIssue i, PersonRef assignee, PersonRef reporter, Instant syncedAt) {
        TaskType type = typeByJiraName.get(i.typeName());
        if (type == null) return Optional.empty();
        StatusCategory cat = switch (i.statusCategoryKey() == null ? "" : i.statusCategoryKey()) {
            case "new" -> StatusCategory.TODO;
            case "indeterminate" -> StatusCategory.IN_PROGRESS;
            default -> null; // done 或未知：不入表
        };
        if (cat == null) return Optional.empty();
        String assigneeDisplay = assignee == null ? UNASSIGNED : assignee.displayText();
        String assigneeOpenId = assignee != null && assignee.mapped() ? assignee.feishuOpenId() : null;
        String reporterText = reporter == null ? "" : reporter.displayText();
        return Optional.of(new TaskRow(null, i.key(), i.summary(), type, i.projectKey(), assigneeOpenId,
                assigneeDisplay, reporterText, i.statusName(), cat, i.priority(), i.created(), i.updated(), syncedAt,
                limitDescription(i.description()), formatComments(i.comments())));
    }

    /** 描述（纯文本）；超过上限时截断并标注。 */
    public static String limitDescription(String description) {
        if (description == null || description.isBlank()) return "";
        if (description.length() <= MAX_DESCRIPTION) return description;
        return description.substring(0, MAX_DESCRIPTION) + "\n…（内容过长，已截断）";
    }

    /**
     * 评论：全部评论按时间从旧到新，每条 "【作者 yyyy-MM-dd HH:mm】" + 换行 + 正文，条间空一行。
     * 总长超过上限时丢弃最早的评论（保留最新的），并在开头标注省略条数；单条过长则截断该条正文。
     */
    public static String formatComments(List<JiraComment> comments) {
        if (comments == null || comments.isEmpty()) return "";
        List<String> blocks = new ArrayList<>();
        for (JiraComment c : comments) {
            String when = c.created() == null ? "" : " " + TIME.format(c.created());
            String text = c.text() == null ? "" : c.text();
            if (text.length() > MAX_ONE_COMMENT) text = text.substring(0, MAX_ONE_COMMENT) + "…（本条过长，已截断）";
            blocks.add("【" + c.author() + when + "】\n" + text);
        }
        int total = blocks.stream().mapToInt(String::length).sum() + 2 * (blocks.size() - 1);
        int dropped = 0;
        while (total > MAX_COMMENTS && blocks.size() - dropped > 1) {
            total -= blocks.get(dropped).length() + 2;
            dropped++;
        }
        String body = String.join("\n\n", blocks.subList(dropped, blocks.size()));
        return dropped == 0 ? body : "…（已省略较早的 " + dropped + " 条评论）\n\n" + body;
    }
}
