package com.dreame.qd.trigger;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** EXT-F08：用飞书官方 SDK 建立长连接（WebSocket，由后端主动连接，无需公网地址），订阅 im.message.receive_v1。断线由 SDK 自动重连。 */
public class LongConnectionListener {
    private static final Logger log = LoggerFactory.getLogger(LongConnectionListener.class);

    private final String appId;
    private final String appSecret;
    private final ManualSyncHandler handler;
    private Client client;

    public LongConnectionListener(String appId, String appSecret, ManualSyncHandler handler) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.handler = handler;
    }

    public void start() {
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        try {
                            EventMessage m = event.getEvent().getMessage();
                            String senderType = event.getEvent().getSender() == null ? null : event.getEvent().getSender().getSenderType();
                            handler.onMessage(m.getMessageId(), m.getMessageType(), m.getContent(), m.getChatId(), senderType);
                        } catch (Throwable t) {
                            log.error("处理消息事件失败：{}", t.toString());
                        }
                    }
                }).build();
        client = new Client.Builder(appId, appSecret).eventHandler(dispatcher).build();
        client.start();
        log.info("飞书长连接已启动（App ID {}）", appId);
    }

    public void stop() {
        if (client != null) client.close();
    }
}
