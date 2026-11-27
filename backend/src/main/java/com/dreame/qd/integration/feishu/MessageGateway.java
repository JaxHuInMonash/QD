package com.dreame.qd.integration.feishu;

/** EXT-F07 发送私聊消息（应用 QD助手 为发送者）。 */
public interface MessageGateway {
    /**
     * @param cardJson 消息卡片 JSON（由 {@link CardBuilder} 生成）
     * @param uuid     去重标识（同一 uuid 在 1 小时内至多成功一条），重试时必须复用同一个
     * @throws com.dreame.qd.model.QdException QD-FEISHU-005 发送失败（retryable 标记是否值得重试）
     */
    void sendCard(String openId, String cardJson, String uuid);
}
