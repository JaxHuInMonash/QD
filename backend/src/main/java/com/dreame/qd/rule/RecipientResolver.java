package com.dreame.qd.rule;

import com.dreame.qd.model.Models.RecipientResolution;
import com.dreame.qd.model.Models.ReminderTarget;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.Models.Undelivered;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * INT-09 识别提醒对象：按经办人归集；跳过"未分配"；扣除排除名单；未对应 → 未送达（账号无法对应）。
 * 主表格没有 Jira 账号字段，所以排除名单以"飞书 open_id / 经办人（显示）文本"两种方式匹配（由调用方准备）。
 */
public final class RecipientResolver {
    private RecipientResolver() {}

    public static RecipientResolution resolve(List<TaskRow> rows, Set<String> excludedOpenIds,
                                              Set<String> excludedUnmappedDisplays) {
        Map<String, List<TaskRow>> byOpenId = new LinkedHashMap<>();
        Map<String, String> nameByOpenId = new LinkedHashMap<>();
        Map<String, Integer> unmapped = new LinkedHashMap<>();
        for (TaskRow r : rows) {
            if (TaskMapper.UNASSIGNED.equals(r.assigneeDisplay())) continue;
            if (r.assigneeOpenId() != null) {
                byOpenId.computeIfAbsent(r.assigneeOpenId(), k -> new ArrayList<>()).add(r);
                nameByOpenId.putIfAbsent(r.assigneeOpenId(), r.assigneeDisplay());
            } else if (r.assigneeDisplay() != null && !r.assigneeDisplay().isBlank()) {
                unmapped.merge(r.assigneeDisplay(), 1, Integer::sum);
            }
        }
        List<ReminderTarget> targets = new ArrayList<>();
        byOpenId.forEach((openId, tasks) -> {
            if (!excludedOpenIds.contains(openId)) targets.add(new ReminderTarget(openId, nameByOpenId.get(openId), tasks));
        });
        List<Undelivered> undelivered = new ArrayList<>();
        unmapped.forEach((display, n) -> {
            if (!excludedUnmappedDisplays.contains(display)) {
                undelivered.add(new Undelivered(display, "账号无法对应（Jira 邮箱在飞书中不存在，且未配置例外对应），名下 " + n + " 条未完成任务"));
            }
        });
        return new RecipientResolution(targets, undelivered);
    }
}
