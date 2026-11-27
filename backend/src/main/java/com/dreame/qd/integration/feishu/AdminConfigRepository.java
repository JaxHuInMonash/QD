package com.dreame.qd.integration.feishu;

import com.dreame.qd.config.QdProperties;
import com.dreame.qd.integration.feishu.BitableClient.Record;
import com.dreame.qd.model.Models.AdminConfig;
import com.dreame.qd.model.Models.Holiday;
import com.dreame.qd.rule.AccountMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INT-07 读取管理表格配置：每次实时读取、不缓存（F4 验收）；按 03 第 3 章规则清洗。
 * 清洗规则：空 Jira 账号忽略；例外对应同账号多行取第一行并告警；节假日类型冲突以"法定节假日"为准；
 * 日期或类型无法识别的节假日行跳过并告警。
 */
public class AdminConfigRepository {
    private static final Logger log = LoggerFactory.getLogger(AdminConfigRepository.class);
    static final String HOLIDAY_TYPE = "法定节假日";
    static final String MAKEUP_TYPE = "调休补班";

    private final BitableClient client;
    private final QdProperties.Admin cfg;
    private final ZoneId zone;

    public AdminConfigRepository(BitableClient client, QdProperties props) {
        this.client = client;
        this.cfg = props.getFeishu().getAdmin();
        this.zone = ZoneId.of(props.getTimezone());
    }

    public AdminConfig load() {
        return new AdminConfig(loadExcludes(), loadMappings(), loadHolidays());
    }

    Set<String> loadExcludes() {
        Set<String> out = new HashSet<>();
        for (Record r : client.searchAll(cfg.getAppToken(), cfg.getExcludeTableId(), List.of("Jira 账号", "姓名", "原因"))) {
            String acc = BitableClient.text(r.fields().get("Jira 账号"));
            if (acc != null && !acc.isBlank()) out.add(AccountMapper.normalize(acc));
        }
        return out;
    }

    public Map<String, String> loadMappings() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Record r : client.searchAll(cfg.getAppToken(), cfg.getMappingTableId(), List.of("Jira 账号", "飞书人员", "备注"))) {
            String acc = BitableClient.text(r.fields().get("Jira 账号"));
            String openId = BitableClient.firstPersonId(r.fields().get("飞书人员"));
            if (acc == null || acc.isBlank() || openId == null) continue;
            String k = AccountMapper.normalize(acc);
            if (out.putIfAbsent(k, openId) != null) log.warn("例外对应表中 Jira 账号 {} 有多行，已取第一行", acc);
        }
        return out;
    }

    List<Holiday> loadHolidays() {
        Map<LocalDate, Holiday> byDate = new HashMap<>();
        for (Record r : client.searchAll(cfg.getAppToken(), cfg.getHolidayTableId(), List.of("日期", "类型", "名称"))) {
            Long ms = BitableClient.millis(r.fields().get("日期"));
            String type = BitableClient.text(r.fields().get("类型"));
            if (ms == null || (!HOLIDAY_TYPE.equals(type) && !MAKEUP_TYPE.equals(type))) {
                log.warn("节假日日历存在日期或类型无法识别的行（record_id={}），已忽略", r.recordId());
                continue;
            }
            LocalDate d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
            Holiday h = new Holiday(d, MAKEUP_TYPE.equals(type), BitableClient.text(r.fields().get("名称")));
            Holiday old = byDate.get(d);
            if (old != null && old.makeup() != h.makeup()) {
                log.warn("节假日日历 {} 的类型冲突，以「法定节假日」为准", d);
                if (!h.makeup()) byDate.put(d, h);
            } else if (old == null) {
                byDate.put(d, h);
            }
        }
        return new ArrayList<>(byDate.values());
    }
}
