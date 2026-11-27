package com.dreame.qd.integration.feishu;

import com.dreame.qd.integration.feishu.FeishuHttp.FeishuApiException;
import com.dreame.qd.model.ErrorCode;
import com.dreame.qd.model.QdException;
import java.util.LinkedHashMap;
import java.util.Map;

public class FeishuMessageGateway implements MessageGateway {
    /** 机器人对该用户没有可用性（不在应用可用范围内）等"重试也没用"的飞书错误码。 */
    private static final java.util.Set<Integer> NO_RETRY_CODES = java.util.Set.of(230013, 230006, 230027, 230099);

    private final FeishuHttp http;

    public FeishuMessageGateway(FeishuHttp http) { this.http = http; }

    @Override
    public void sendCard(String openId, String cardJson, String uuid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receive_id", openId);
        body.put("msg_type", "interactive");
        body.put("content", cardJson);
        if (uuid != null) body.put("uuid", uuid);
        try {
            http.call("POST", "/open-apis/im/v1/messages?receive_id_type=open_id", body);
        } catch (FeishuApiException e) {
            boolean retry = e.retryable() || (e.errorCode() == ErrorCode.SYS && !NO_RETRY_CODES.contains(e.feishuCode()));
            String reason = e.feishuCode() == 230013 ? "该用户不在应用 QD助手 的可用范围内，无法私聊" : e.getMessage();
            throw new QdException(ErrorCode.FEISHU_SEND, retry, reason, e);
        } catch (QdException e) {
            throw new QdException(ErrorCode.FEISHU_SEND, e.retryable(), e.getMessage(), e);
        }
    }
}
