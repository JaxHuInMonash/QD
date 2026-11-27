package com.dreame.qd.trigger;

import com.dreame.qd.model.Models.SyncTrigger;
import com.dreame.qd.service.SyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EVT-01 处理：识别 #QD立即同步 → 同步进行中则忽略 → 执行手动同步（MANUAL）；不发送提醒（F2.3）。
 * 在独立线程执行，避免阻塞长连接的事件线程；按消息 ID 去重（飞书事件可能重复推送）。
 */
public class ManualSyncHandler {
    private static final Logger log = LoggerFactory.getLogger(ManualSyncHandler.class);
    private static final int DEDUP_SIZE = 200;

    private final SyncService syncService;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final Set<String> seen = new LinkedHashSet<>();

    public ManualSyncHandler(SyncService syncService, ObjectMapper mapper) {
        this(syncService, mapper, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "manual-sync");
            t.setDaemon(true);
            return t;
        }));
    }

    public ManualSyncHandler(SyncService syncService, ObjectMapper mapper, ExecutorService executor) {
        this.syncService = syncService;
        this.mapper = mapper;
        this.executor = executor;
    }

    /** @return 是否已受理（识别为立即同步请求且已提交执行） */
    public boolean onMessage(String messageId, String messageType, String contentJson, String chatId, String senderType) {
        String text = SyncRequestParser.extractText(mapper, messageType, contentJson);
        if (!SyncRequestParser.isSyncRequest(text)) return false; // 非本系统消息，忽略
        synchronized (seen) {
            if (messageId != null && !seen.add(messageId)) return false;
            if (seen.size() > DEDUP_SIZE) seen.remove(seen.iterator().next());
        }
        log.info("收到立即同步请求（messageType={}, senderType={}, chat={}）", messageType, senderType, chatId);
        if (syncService.isRunning()) {
            log.info("已有同步正在进行，本次立即同步请求忽略");
            return false;
        }
        executor.submit(() -> {
            try {
                syncService.sync(SyncTrigger.MANUAL);
            } catch (Throwable t) { // 触发层兜底，保证不影响后续请求
                log.error("立即同步异常：{}", t.toString(), t);
            }
        });
        return true;
    }
}
