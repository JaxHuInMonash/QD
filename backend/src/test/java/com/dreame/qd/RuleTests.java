package com.dreame.qd;

import static org.assertj.core.api.Assertions.assertThat;

import com.dreame.qd.model.Models.Holiday;
import com.dreame.qd.model.Models.JiraIssue;
import com.dreame.qd.model.Models.JiraUser;
import com.dreame.qd.model.Models.PersonRef;
import com.dreame.qd.model.Models.RecipientResolution;
import com.dreame.qd.model.Models.ReminderMessage;
import com.dreame.qd.model.Models.ReminderTarget;
import com.dreame.qd.model.Models.StatusCategory;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.Models.TaskType;
import com.dreame.qd.rule.AccountMapper;
import com.dreame.qd.rule.RecipientResolver;
import com.dreame.qd.rule.ReminderComposer;
import com.dreame.qd.rule.TaskMapper;
import com.dreame.qd.rule.WorkdayCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleTests {
    static final Map<String, List<String>> TYPES = Map.of("Story", List.of("Story"), "Task", List.of("任务"), "Bug", List.of("Bug（软件测试）"));
    static final TaskMapper MAPPER = new TaskMapper(TYPES, "https://jira.example.com/");

    // ---------- WorkdayCalendar（INT-03） ----------
    @Test
    void workdayRules() {
        List<Holiday> h = List.of(
                new Holiday(LocalDate.of(2026, 10, 1), false, "国庆节"),        // 周四，法定节假日
                new Holiday(LocalDate.of(2026, 10, 10), true, "调休补班"),      // 周六，补班
                new Holiday(LocalDate.of(2026, 10, 2), false, "冲突"),
                new Holiday(LocalDate.of(2026, 10, 2), true, "冲突"));          // 同日类型冲突 → 法定节假日
        assertThat(WorkdayCalendar.isWorkday(LocalDate.of(2026, 10, 1), h)).isFalse();
        assertThat(WorkdayCalendar.isWorkday(LocalDate.of(2026, 10, 10), h)).isTrue();
        assertThat(WorkdayCalendar.isWorkday(LocalDate.of(2026, 10, 2), h)).isFalse();
        assertThat(WorkdayCalendar.isWorkday(LocalDate.of(2026, 10, 12), h)).isTrue();   // 周一
        assertThat(WorkdayCalendar.isWorkday(LocalDate.of(2026, 10, 11), h)).isFalse();  // 周日
        assertThat(WorkdayCalendar.isWorkday(LocalDate.of(2027, 3, 1), List.of())).isTrue(); // 未录入年份按周一至周五
        assertThat(WorkdayCalendar.isYearEntered(2026, h)).isTrue();
        assertThat(WorkdayCalendar.isYearEntered(2027, h)).isFalse();
    }

    // ---------- AccountMapper（INT-08） ----------
    @Test
    void accountMappingByEmailThenException() {
        JiraUser a = new JiraUser("HuHK", "胡桓康", "Huhk@Dreame.tech");
        JiraUser b = new JiraUser("chenchen", "陈晨", "chen@partner.org");
        JiraUser c = new JiraUser("sunwei", "孙伟", "sunwei@dreame.tech");
        Map<String, PersonRef> m = AccountMapper.map(List.of(a, b, c, a),
                Map.of("huhk@dreame.tech", "ou_a"), Map.of("chenchen", "ou_b"));
        assertThat(m).hasSize(3);
        assertThat(m.get("huhk").feishuOpenId()).isEqualTo("ou_a");
        assertThat(m.get("huhk").mapped()).isTrue();
        assertThat(m.get("chenchen").feishuOpenId()).isEqualTo("ou_b");
        assertThat(m.get("sunwei").mapped()).isFalse();
        assertThat(m.get("sunwei").displayText()).isEqualTo("孙伟（未对应）");
    }

    // ---------- TaskMapper（R2、Q4） ----------
    private JiraIssue issue(String key, String type, String cat, JiraUser assignee) {
        return new JiraIssue(key, "标题" + key, type, key.split("-")[0], assignee, new JiraUser("zz", "朱卓", "z@dreame.tech"),
                "开发中", cat, "高", Instant.parse("2026-10-08T02:12:00Z"), Instant.parse("2026-11-24T10:30:00Z"));
    }

    @Test
    void taskMapperFiltersTypeAndCategory() {
        PersonRef p = new PersonRef(new JiraUser("a", "甲", "a@d.tech"), "ou_a", "甲", true);
        Instant at = Instant.parse("2026-11-26T08:50:00Z");
        assertThat(MAPPER.toRow(issue("VERA1-1", "Story", "indeterminate", null), p, p, at)).get()
                .satisfies(r -> { assertThat(r.type()).isEqualTo(TaskType.STORY); assertThat(r.category()).isEqualTo(StatusCategory.IN_PROGRESS); });
        assertThat(MAPPER.toRow(issue("VERA1-2", "任务", "new", null), p, p, at)).get().extracting(TaskRow::category).isEqualTo(StatusCategory.TODO);
        assertThat(MAPPER.toRow(issue("BUGA1-3", "Bug（软件测试）", "new", null), p, p, at)).isPresent();
        assertThat(MAPPER.toRow(issue("VERA1-4", "Story", "done", null), p, p, at)).isEmpty();          // 完成类不入表
        assertThat(MAPPER.toRow(issue("VERA1-5", "子任务", "new", null), p, p, at)).isEmpty();           // 子任务不纳入
        assertThat(MAPPER.toRow(issue("VERA1-6", "Fix", "new", null), p, p, at)).isEmpty();
        assertThat(MAPPER.toRow(issue("VERA1-7", "BUG（实验室）", "new", null), p, p, at)).isEmpty();    // D1：看板仅含 Bug（软件测试）
    }

    @Test
    void unassignedAndUnmappedDisplay() {
        Instant at = Instant.now();
        TaskRow un = MAPPER.toRow(issue("VERA1-1", "Story", "new", null), null, null, at).orElseThrow();
        assertThat(un.assigneeDisplay()).isEqualTo("未分配");
        assertThat(un.assigneeOpenId()).isNull();
        PersonRef unmapped = new PersonRef(new JiraUser("s", "孙伟", "s@x"), null, "孙伟（未对应）", false);
        TaskRow r = MAPPER.toRow(issue("VERA1-2", "Story", "new", unmapped.jiraUser()), unmapped, unmapped, at).orElseThrow();
        assertThat(r.assigneeOpenId()).isNull();
        assertThat(r.assigneeDisplay()).isEqualTo("孙伟（未对应）");
        assertThat(MAPPER.browseUrl("VERA1-2")).isEqualTo("https://jira.example.com/browse/VERA1-2");
    }

    // ---------- RecipientResolver（INT-09） ----------
    static TaskRow row(String key, TaskType t, String openId, String display, String created) {
        return new TaskRow("rec_" + key, key, "标题" + key, t, "P", openId, display, "报告人", "开发中", StatusCategory.IN_PROGRESS, "中",
                Instant.parse(created), Instant.parse(created), Instant.parse("2026-11-26T08:50:00Z"));
    }

    @Test
    void recipientResolution() {
        List<TaskRow> rows = List.of(
                row("A-1", TaskType.STORY, "ou_a", "甲", "2026-10-01T00:00:00Z"),
                row("A-2", TaskType.TASK, "ou_a", "甲", "2026-10-02T00:00:00Z"),
                row("B-1", TaskType.BUG, "ou_b", "乙", "2026-10-03T00:00:00Z"),        // 被排除
                row("C-1", TaskType.BUG, null, "孙伟（未对应）", "2026-10-04T00:00:00Z"),  // 未对应 → 未送达
                row("D-1", TaskType.BUG, null, "未分配", "2026-10-05T00:00:00Z"),          // 未分配 → 忽略
                row("E-1", TaskType.BUG, null, "赵磊（未对应）", "2026-10-06T00:00:00Z")); // 未对应但被排除
        RecipientResolution r = RecipientResolver.resolve(rows, Set.of("ou_b"), Set.of("赵磊（未对应）"));
        assertThat(r.targets()).extracting(ReminderTarget::openId).containsExactly("ou_a");
        assertThat(r.targets().get(0).tasks()).hasSize(2);
        assertThat(r.undelivered()).hasSize(1);
        assertThat(r.undelivered().get(0).person()).isEqualTo("孙伟（未对应）");
    }

    // ---------- ReminderComposer（INT-10） ----------
    @Test
    void composerLimitsToThreeOldestAndShowsZero() {
        List<TaskRow> tasks = new java.util.ArrayList<>();
        for (int i = 5; i >= 1; i--) tasks.add(row("VERA-" + i, TaskType.STORY, "ou_a", "甲", "2026-10-0" + i + "T00:00:00Z"));
        tasks.add(row("VERA-9", TaskType.TASK, "ou_a", "甲", "2026-10-09T00:00:00Z"));
        ReminderMessage m = new ReminderComposer(MAPPER, "https://view").compose(new ReminderTarget("ou_a", "甲", tasks), Instant.parse("2026-11-26T08:50:00Z"));
        assertThat(m.sections()).extracting(s -> s.type()).containsExactly(TaskType.STORY, TaskType.TASK, TaskType.BUG);
        assertThat(m.sections().get(0).total()).isEqualTo(5);
        assertThat(m.sections().get(0).items()).extracting(i -> i.key()).containsExactly("VERA-1", "VERA-2", "VERA-3"); // 早 → 晚
        assertThat(m.sections().get(1).total()).isEqualTo(1);
        assertThat(m.sections().get(2).total()).isZero();                                                              // 0 也显示
        assertThat(m.sections().get(0).items().get(0).url()).isEqualTo("https://jira.example.com/browse/VERA-1");
        assertThat(m.viewAllUrl()).isEqualTo("https://view");
    }
}
