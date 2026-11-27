package com.dreame.qd.rule;

import com.dreame.qd.model.Models.ReminderItem;
import com.dreame.qd.model.Models.ReminderMessage;
import com.dreame.qd.model.Models.ReminderSection;
import com.dreame.qd.model.Models.ReminderTarget;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.Models.TaskType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** INT-10 生成提醒消息：Story / Task / Bug 三段，每段"共 N 条"（0 也显示）+ 最多 3 条，按创建时间早→晚。 */
public final class ReminderComposer {
    public static final int MAX_ITEMS = 3;

    private final TaskMapper taskMapper;
    private final String viewAllUrl;

    public ReminderComposer(TaskMapper taskMapper, String viewAllUrl) {
        this.taskMapper = taskMapper;
        this.viewAllUrl = viewAllUrl;
    }

    public ReminderMessage compose(ReminderTarget target, Instant dataAsOf) {
        List<ReminderSection> sections = java.util.Arrays.stream(TaskType.values()).map(type -> {
            List<TaskRow> list = target.tasks().stream().filter(r -> r.type() == type)
                    .sorted(Comparator.comparing(TaskRow::created, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(TaskRow::key)).toList();
            List<ReminderItem> items = list.stream().limit(MAX_ITEMS)
                    .map(r -> new ReminderItem(r.key(), taskMapper.browseUrl(r.key()), r.title(), r.status())).toList();
            return new ReminderSection(type, list.size(), items);
        }).toList();
        return new ReminderMessage(target.openId(), dataAsOf, sections, viewAllUrl);
    }
}
