package com.dreame.qd.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class FeishuContactGateway implements ContactGateway {
    private static final int BATCH = 50;
    private final FeishuHttp http;

    public FeishuContactGateway(FeishuHttp http) { this.http = http; }

    @Override
    public Map<String, String> findOpenIdsByEmail(Collection<String> emails) {
        List<String> list = emails.stream().filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase(Locale.ROOT)).distinct().collect(Collectors.toCollection(ArrayList::new));
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < list.size(); i += BATCH) {
            List<String> chunk = list.subList(i, Math.min(i + BATCH, list.size()));
            JsonNode data = http.call("POST", "/open-apis/contact/v3/users/batch_get_id?user_id_type=open_id",
                    Map.of("emails", chunk, "include_resigned", false));
            for (JsonNode u : data.path("user_list")) {
                if (u.hasNonNull("user_id") && u.hasNonNull("email")) {
                    out.put(u.get("email").asText().toLowerCase(Locale.ROOT), u.get("user_id").asText());
                }
            }
        }
        return out;
    }
}
