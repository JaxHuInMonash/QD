package com.dreame.qd.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** EXT-F02～F05：多维表格记录的查询与批量增改删（分页 / 分批）。 */
public class BitableClient {
    static final int PAGE_SIZE = 500;
    static final int BATCH_SIZE = 100;   // 含描述 / 评论后单行较大，按 100 行一批，避免请求体过大

    public record Record(String recordId, JsonNode fields) {}

    private final FeishuHttp http;

    public BitableClient(FeishuHttp http) { this.http = http; }

    private static String recordsPath(String app, String table, String suffix) {
        return "/open-apis/bitable/v1/apps/" + app + "/tables/" + table + "/records" + suffix;
    }

    /** 取完所有分页。fieldNames 为空表示全部字段。 */
    public List<Record> searchAll(String app, String table, List<String> fieldNames) {
        List<Record> out = new ArrayList<>();
        String pageToken = null;
        do {
            String path = recordsPath(app, table, "/search?user_id_type=open_id&page_size=" + PAGE_SIZE
                    + (pageToken == null ? "" : "&page_token=" + pageToken));
            ObjectNode body = http.mapper().createObjectNode();
            if (fieldNames != null && !fieldNames.isEmpty()) {
                ArrayNode arr = body.putArray("field_names");
                fieldNames.forEach(arr::add);
            }
            JsonNode data = http.call("POST", path, body);
            for (JsonNode it : data.path("items")) out.add(new Record(it.path("record_id").asText(), it.path("fields")));
            pageToken = data.path("has_more").asBoolean(false) ? data.path("page_token").asText(null) : null;
        } while (pageToken != null);
        return out;
    }

    public void batchCreate(String app, String table, List<Map<String, Object>> fieldsList) {
        for (int i = 0; i < fieldsList.size(); i += BATCH_SIZE) {
            List<Map<String, Object>> records = new ArrayList<>();
            for (Map<String, Object> f : fieldsList.subList(i, Math.min(i + BATCH_SIZE, fieldsList.size()))) records.add(Map.of("fields", f));
            http.call("POST", recordsPath(app, table, "/batch_create?user_id_type=open_id"), Map.of("records", records));
        }
    }

    /** records 的每个元素含 record_id 与 fields。 */
    public void batchUpdate(String app, String table, List<Map<String, Object>> records) {
        for (int i = 0; i < records.size(); i += BATCH_SIZE) {
            http.call("POST", recordsPath(app, table, "/batch_update?user_id_type=open_id"),
                    Map.of("records", records.subList(i, Math.min(i + BATCH_SIZE, records.size()))));
        }
    }

    public void batchDelete(String app, String table, List<String> recordIds) {
        for (int i = 0; i < recordIds.size(); i += BATCH_SIZE) {
            http.call("POST", recordsPath(app, table, "/batch_delete"),
                    Map.of("records", recordIds.subList(i, Math.min(i + BATCH_SIZE, recordIds.size()))));
        }
    }

    // ---------------- 字段值解析（兼容飞书不同返回形态） ----------------

    /** 文本：字符串、{text}、或富文本片段数组 [{text}]。 */
    public static String text(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        if (v.isTextual()) return v.asText();
        if (v.isNumber()) return v.asText();
        if (v.isObject()) return v.hasNonNull("text") ? v.get("text").asText() : (v.hasNonNull("link") ? v.get("link").asText() : null);
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode e : v) {
                String t = text(e);
                if (t != null) sb.append(t);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    /** 人员字段首个 open_id。 */
    public static String firstPersonId(JsonNode v) {
        if (v != null && v.isArray() && !v.isEmpty() && v.get(0).hasNonNull("id")) return v.get(0).get("id").asText();
        return null;
    }

    /** 日期字段（毫秒时间戳）。 */
    public static Long millis(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        if (v.isNumber()) return v.asLong();
        if (v.isTextual()) { try { return Long.parseLong(v.asText()); } catch (NumberFormatException e) { return null; } }
        return null;
    }
}
