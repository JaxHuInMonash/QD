package com.dreame.qd.rule;

import com.dreame.qd.model.Models.JiraIssue;
import com.dreame.qd.model.Models.PersonRef;
import com.dreame.qd.model.Models.StatusCategory;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.Models.TaskType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Jira Issue → 主表格行（03 4.2）；同时执行"只保留未完成、只保留三种类型"的二次校验（R2）。 */
public final class TaskMapper {
    public static final String UNASSIGNED = "未分配";

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
                assigneeDisplay, reporterText, i.statusName(), cat, i.priority(), i.created(), i.updated(), syncedAt));
    }
}
