package com.dreame.qd.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * EVT-01 消息解析：识别以 #QD立即同步 开头的消息（需求 v1.5：不校验点击人与来源）。
 * 工作流发出的是卡片消息（interactive），正文在 content.user_dsl.body.elements[] 的 markdown 元素中（技术验证 V3）。
 */
public final class SyncRequestParser {
    public static final String KEYWORD = "#QD立即同步";

    private SyncRequestParser() {}

    public static String extractText(ObjectMapper mapper, String messageType, String contentJson) {
        if (contentJson == null) return "";
        try {
            JsonNode c = mapper.readTree(contentJson);
            if ("interactive".equals(messageType) && c.hasNonNull("user_dsl")) {
                JsonNode dsl = mapper.readTree(c.get("user_dsl").asText());
                List<String> parts = new ArrayList<>();
                for (JsonNode e : dsl.path("body").path("elements")) {
                    if ("markdown".equals(e.path("tag").asText()) && e.hasNonNull("content") && !e.get("content").asText().isBlank()) {
                        parts.add(e.get("content").asText());
                    }
                }
                return String.join("\n", parts);
            }
            return c.hasNonNull("text") ? c.get("text").asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isSyncRequest(String text) {
        return text != null && text.stripLeading().startsWith(KEYWORD);
    }
}
