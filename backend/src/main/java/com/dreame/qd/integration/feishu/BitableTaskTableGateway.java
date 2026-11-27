package com.dreame.qd.integration.feishu;

import com.dreame.qd.config.QdProperties;
import com.dreame.qd.integration.feishu.BitableClient.Record;
import com.dreame.qd.model.Models.StatusCategory;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.Models.TaskType;
import com.dreame.qd.rule.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 主表格网关实现：字段名严格对应 03 2.1，不得擅自新增或修改。 */
public class BitableTaskTableGateway implements TaskTableGateway {
    private static final Logger log = LoggerFactory.getLogger(BitableTaskTableGateway.class);

    static final String F_KEY = "Issue 编号";
    static final String F_TITLE = "标题";
    static final String F_TYPE = "类型";
    static final String F_PROJECT = "所属项目";
    static final String F_ASSIGNEE = "经办人";
    static final String F_ASSIGNEE_DISPLAY = "经办人（显示）";
    static final String F_REPORTER = "报告人";
    static final String F_STATUS = "Jira 状态";
    static final String F_CATEGORY = "状态类别";
    static final String F_PRIORITY = "优先级";
    static final String F_CREATED = "创建时间";
    static final String F_UPDATED = "最后更新时间";
    static final String F_SYNCED = "最后同步时间";
    static final String F_DESCRIPTION = "描述";
    static final String F_COMMENTS = "评论";
    /** 读取现有行时只需要这些字段；描述 / 评论每次同步整体覆盖写入，读回来没有意义且体积大。 */
    static final List<String> ALL_FIELDS = List.of(F_KEY, F_TITLE, F_TYPE, F_PROJECT, F_ASSIGNEE, F_ASSIGNEE_DISPLAY,
            F_REPORTER, F_STATUS, F_CATEGORY, F_PRIORITY, F_CREATED, F_UPDATED, F_SYNCED);

    private final BitableClient client;
    private final QdProperties.Main cfg;
    private final TaskMapper taskMapper;

    private final String tableId;

    public BitableTaskTableGateway(BitableClient client, QdProperties props, TaskMapper taskMapper) {
        this(client, props, taskMapper, props.getFeishu().getMain().getTaskTableId());
    }

    /** 指定数据表（主表格或副本表格，字段结构相同）。 */
    public BitableTaskTableGateway(BitableClient client, QdProperties props, TaskMapper taskMapper, String tableId) {
        this.client = client;
        this.cfg = props.getFeishu().getMain();
        this.taskMapper = taskMapper;
        this.tableId = tableId;
    }

    @Override
    public List<TaskRow> listAll() {
        List<TaskRow> out = new ArrayList<>();
        for (Record r : client.searchAll(cfg.getAppToken(), tableId, ALL_FIELDS)) {
            JsonNode f = r.fields();
            String key = BitableClient.text(f.get(F_KEY));
            if (key == null || key.isBlank()) {
                log.warn("主表格存在 Issue 编号为空的记录（record_id={}），按已完成对齐时将被删除", r.recordId());
                out.add(new TaskRow(r.recordId(), "", null, null, null, null, null, null, null, null, null, null, null, null));
                continue;
            }
            out.add(new TaskRow(r.recordId(), key.trim(), BitableClient.text(f.get(F_TITLE)),
                    TaskType.fromLabel(BitableClient.text(f.get(F_TYPE))), BitableClient.text(f.get(F_PROJECT)),
                    BitableClient.firstPersonId(f.get(F_ASSIGNEE)), BitableClient.text(f.get(F_ASSIGNEE_DISPLAY)),
                    BitableClient.text(f.get(F_REPORTER)), BitableClient.text(f.get(F_STATUS)),
                    StatusCategory.fromLabel(BitableClient.text(f.get(F_CATEGORY))), BitableClient.text(f.get(F_PRIORITY)),
                    instant(f.get(F_CREATED)), instant(f.get(F_UPDATED)), instant(f.get(F_SYNCED))));
        }
        return out;
    }

    @Override
    public void createBatch(List<TaskRow> rows) {
        if (rows.isEmpty()) return;
        client.batchCreate(cfg.getAppToken(), tableId, rows.stream().map(r -> fields(r, false)).toList());
    }

    @Override
    public void updateBatch(List<TaskRow> rows) {
        if (rows.isEmpty()) return;
        client.batchUpdate(cfg.getAppToken(), tableId,
                rows.stream().map(r -> Map.<String, Object>of("record_id", r.recordId(), "fields", fields(r, true))).toList());
    }

    @Override
    public void deleteBatch(List<String> recordIds) {
        if (recordIds.isEmpty()) return;
        client.batchDelete(cfg.getAppToken(), tableId, recordIds);
    }

    /** 写入值格式见 03 2.1：超链接对象、人员 open_id、日期毫秒时间戳。更新时对应不上的经办人写空数组以清空。 */
    Map<String, Object> fields(TaskRow r, boolean update) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(F_KEY, Map.of("text", r.key(), "link", taskMapper.browseUrl(r.key())));
        f.put(F_TITLE, nz(r.title()));
        f.put(F_TYPE, r.type().label());
        f.put(F_PROJECT, nz(r.project()));
        if (r.assigneeOpenId() != null) f.put(F_ASSIGNEE, List.of(Map.of("id", r.assigneeOpenId())));
        else if (update) f.put(F_ASSIGNEE, List.of());
        f.put(F_ASSIGNEE_DISPLAY, nz(r.assigneeDisplay()));
        f.put(F_REPORTER, nz(r.reporter()));
        f.put(F_STATUS, nz(r.status()));
        f.put(F_CATEGORY, r.category().label());
        if (r.priority() != null) f.put(F_PRIORITY, r.priority());
        if (r.created() != null) f.put(F_CREATED, r.created().toEpochMilli());
        if (r.updated() != null) f.put(F_UPDATED, r.updated().toEpochMilli());
        if (r.syncedAt() != null) f.put(F_SYNCED, r.syncedAt().toEpochMilli());
        f.put(F_DESCRIPTION, nz(r.description()));
        f.put(F_COMMENTS, nz(r.comments()));
        return f;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static Instant instant(JsonNode v) {
        Long ms = BitableClient.millis(v);
        return ms == null ? null : Instant.ofEpochMilli(ms);
    }
}
