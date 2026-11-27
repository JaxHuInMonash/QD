package com.dreame.qd.integration.local;

import com.dreame.qd.model.Models.DailyState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INT-13 本地状态文件 reminder-state.json（03 第 5 章）。只保留当天数据，日期变化即视为空状态；每次写入立即落盘。
 * 文件损坏时按空状态处理并写警告（风险 R6：影响范围限于当天）。
 */
public class DailyStateStore {
    private static final Logger log = LoggerFactory.getLogger(DailyStateStore.class);

    private final Path file;
    private final ObjectMapper mapper;

    public DailyStateStore(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    public synchronized DailyState load(LocalDate today) {
        DailyState s = read();
        if (s == null || !today.equals(s.date())) {
            // 日期变化：清空当天运行与已发送记录，但保留年底日历检查日期
            LocalDate last = s == null ? null : s.lastCalendarCheck();
            s = new DailyState(today, new HashMap<>(), new HashSet<>(), last);
        }
        return s;
    }

    public synchronized void markRun(LocalDate today, String job, Instant at) {
        DailyState s = load(today);
        s.runs().put(job, at);
        write(s);
    }

    public synchronized boolean isSent(LocalDate today, String openId) {
        return load(today).sentOpenIds().contains(openId);
    }

    public synchronized void markSent(LocalDate today, String openId) {
        DailyState s = load(today);
        s.sentOpenIds().add(openId);
        write(s);
    }

    public synchronized void markCalendarChecked(LocalDate today) {
        DailyState s = load(today);
        write(new DailyState(s.date(), s.runs(), s.sentOpenIds(), today));
    }

    // ---------------- 文件读写 ----------------

    private DailyState read() {
        if (!Files.exists(file)) return null;
        try {
            JsonNode n = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Instant> runs = new HashMap<>();
            n.path("runs").fields().forEachRemaining(e -> runs.put(e.getKey(), java.time.OffsetDateTime.parse(e.getValue().asText()).toInstant()));
            HashSet<String> sent = new HashSet<>();
            n.path("sentOpenIds").forEach(x -> sent.add(x.asText()));
            LocalDate last = n.hasNonNull("lastCalendarCheck") ? LocalDate.parse(n.get("lastCalendarCheck").asText()) : null;
            return new DailyState(LocalDate.parse(n.path("date").asText()), runs, sent, last);
        } catch (IOException | RuntimeException e) {
            log.warn("本地状态文件无法读取，按空状态处理（{}）", e.getClass().getSimpleName());
            return null;
        }
    }

    private void write(DailyState s) {
        ObjectNode root = mapper.createObjectNode();
        root.put("date", s.date().toString());
        ObjectNode runs = root.putObject("runs");
        s.runs().forEach((k, v) -> runs.put(k, v.atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString()));
        ArrayNode sent = root.putArray("sentOpenIds");
        s.sentOpenIds().stream().sorted().forEach(sent::add);
        if (s.lastCalendarCheck() != null) root.put("lastCalendarCheck", s.lastCalendarCheck().toString());
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 写不进状态文件不应中断业务；最坏情况是重启后当天可能重复发送（R6）
            log.error("本地状态文件写入失败：{}", e.getClass().getSimpleName());
        }
    }
}
