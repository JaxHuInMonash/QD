package com.dreame.qd.service;

import com.dreame.qd.config.QdProperties;
import com.dreame.qd.integration.feishu.CardBuilder;
import com.dreame.qd.integration.feishu.MessageGateway;
import com.dreame.qd.model.Models.SyncResult;
import com.dreame.qd.model.Models.Undelivered;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INT-12 异常通知：同时私聊运维负责人与备份负责人（01 Q3）。
 * 通知本身发送失败只写运行日志，不再递归通知。
 */
public class AlertService {
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MessageGateway messages;
    private final CardBuilder cards;
    private final QdProperties.Feishu cfg;
    private final Clock clock;
    private final ZoneId zone;

    public AlertService(MessageGateway messages, CardBuilder cards, QdProperties props, Clock clock) {
        this.messages = messages;
        this.cards = cards;
        this.cfg = props.getFeishu();
        this.clock = clock;
        this.zone = ZoneId.of(props.getTimezone());
    }

    /** @param dataAsOf 主表格数据截至时间（最近一次成功同步），未知时为空 */
    public void syncFailed(SyncResult r, Instant dataAsOf) {
        boolean authFailure = "QD-JIRA-001".equals(r.code()) || "QD-JIRA-002".equals(r.code());
        String note = authFailure ? "认证失败未重试，请检查密码或账号是否被锁定。"
                : (r.retried() > 0 ? "已自动重试 1 次仍失败。" : "该错误不可重试。");
        send(cards.syncFailed(r.trigger().label(), AT.format(r.finishedAt().atZone(zone)), r.code() + " " + r.message(), note, dataAsOf));
    }

    public void undelivered(LocalDate date, List<Undelivered> list) {
        if (list.isEmpty()) return;
        send(cards.undelivered(date.toString(), list));
    }

    public void calendarMissing(int year) {
        send(cards.calendarMissing(year));
    }

    /** 通用异常通知（如提醒流程无法读取主表格、启动时配置错误）。 */
    public void custom(String title, String body) {
        send(cards.generic(title, body));
    }

    private void send(String cardJson) {
        Set<String> receivers = new LinkedHashSet<>();
        receivers.addAll(cfg.getOpsOpenIds());
        receivers.addAll(cfg.getBackupOpenIds());
        String uuid = UUID.randomUUID().toString();
        for (String openId : receivers) {
            try {
                messages.sendCard(openId, cardJson, uuid + "-" + Math.abs(openId.hashCode()));
            } catch (RuntimeException e) {
                log.error("异常通知发送失败（接收人 {}）：{}", mask(openId), e.getMessage());
            }
        }
    }

    private static String mask(String openId) {
        return openId.length() <= 8 ? "***" : openId.substring(0, 6) + "***";
    }
}
