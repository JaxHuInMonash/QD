package com.dreame.qd.integration.feishu;

import com.dreame.qd.model.Models.ReminderItem;
import com.dreame.qd.model.Models.ReminderMessage;
import com.dreame.qd.model.Models.ReminderSection;
import com.dreame.qd.model.Models.Undelivered;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 把消息数据（03 第 7 章）渲染为飞书消息卡片 JSON（schema 2.0，技术验证 V4 已实测可发送）。 */
public class CardBuilder {
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ObjectMapper mapper;
    private final ZoneId zone;
    private final String reminderIntro;

    public CardBuilder(ObjectMapper mapper, ZoneId zone) {
        this(mapper, zone, "请在下班前更新以下任务在 Jira 中的状态：");
    }

    public CardBuilder(ObjectMapper mapper, ZoneId zone, String reminderIntro) {
        this.mapper = mapper;
        this.zone = zone;
        this.reminderIntro = reminderIntro;
    }

    public String reminder(ReminderMessage m) {
        ArrayNode els = mapper.createArrayNode();
        md(els, reminderIntro);
        for (ReminderSection s : m.sections()) {
            StringBuilder sb = new StringBuilder("**").append(s.type().label()).append("（共 ").append(s.total()).append(" 条）**");
            for (ReminderItem it : s.items()) {
                sb.append("\n[").append(escape(it.key())).append("](").append(it.url()).append(") ")
                        .append(escape(it.title())).append(" —— ").append(escape(it.status()));
            }
            md(els, sb.toString());
        }
        els.addObject().put("tag", "hr");
        StringBuilder foot = new StringBuilder();
        if (m.viewAllUrl() != null && !m.viewAllUrl().isBlank()) foot.append("[查看全部 → 我的未完成](").append(m.viewAllUrl()).append(")\n");
        foot.append("<font color='grey'>数据截至 ").append(m.dataAsOf() == null ? "—" : format(m.dataAsOf())).append("</font>");
        md(els, foot.toString());
        return card("blue", "📋 Jira 待更新提醒", els);
    }

    public String syncFailed(String syncLabel, String at, String reason, String retriedNote, Instant dataAsOf) {
        ArrayNode els = mapper.createArrayNode();
        md(els, "**" + escape(syncLabel) + "（" + at + "）失败**\n原因：" + escape(reason) + "\n" + retriedNote
                + "\n主表格保持上一次成功同步的数据（截至 " + (dataAsOf == null ? "—" : format(dataAsOf)) + "）。"
                + "\n由运维负责人处理；其不在时由备份负责人接替。");
        return card("red", "⚠️ 同步失败通知", els);
    }

    public String undelivered(String date, List<Undelivered> list) {
        ArrayNode els = mapper.createArrayNode();
        StringBuilder sb = new StringBuilder("**" + date + " 17:00 提醒有 " + list.size() + " 人未送达：**");
        for (Undelivered u : list) sb.append("\n· ").append(escape(u.person())).append("：").append(escape(u.reason()));
        sb.append("\n可在管理表格的「例外对应表 / 排除名单」中处理。");
        md(els, sb.toString());
        return card("orange", "⚠️ 提醒未送达汇总", els);
    }

    public String calendarMissing(int year) {
        ArrayNode els = mapper.createArrayNode();
        md(els, "管理表格中尚未录入 **" + year + "** 年的法定节假日与调休补班安排。\n"
                + "未录入期间将按「周一至周五为工作日」判断，可能导致节假日误发提醒、调休补班日漏发。\n"
                + "请在管理表格「节假日日历」中录入；录入后不再提醒（每周一 09:00 检查）。");
        return card("orange", "⚠️ 请录入次年节假日日历", els);
    }

    public String generic(String title, String body) {
        ArrayNode els = mapper.createArrayNode();
        md(els, body);
        return card("red", "⚠️ " + title, els);
    }

    private String format(Instant t) { return SHORT.format(t.atZone(zone)); }

    private String card(String color, String title, ArrayNode elements) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema", "2.0");
        ObjectNode header = root.putObject("header");
        header.put("template", color);
        header.putObject("title").put("tag", "plain_text").put("content", title);
        root.putObject("body").set("elements", elements);
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("卡片序列化失败", e);
        }
    }

    private static void md(ArrayNode els, String content) {
        els.addObject().put("tag", "markdown").put("content", content);
    }

    /** Jira 标题可能含 markdown / 链接语法字符，替换为全角以避免破坏排版。 */
    static String escape(String s) {
        if (s == null) return "";
        return s.replace('[', '［').replace(']', '］').replace('<', '＜').replace('>', '＞')
                .replace('*', '＊').replace('`', '\'').replace("\r", "").replace('\n', ' ');
    }
}
