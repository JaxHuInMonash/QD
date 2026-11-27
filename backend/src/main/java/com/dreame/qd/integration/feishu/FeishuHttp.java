package com.dreame.qd.integration.feishu;

import com.dreame.qd.config.QdProperties;
import com.dreame.qd.model.ErrorCode;
import com.dreame.qd.model.QdException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * 飞书开放平台 REST 调用（应用身份 tenant_access_token，EXT-F01）。
 * 令牌只缓存在内存，日志中不出现令牌、App Secret 与请求体。
 */
public class FeishuHttp {
    /** 无权限 / 表格权限不足相关的飞书错误码。 */
    private static final Set<Integer> FORBIDDEN_CODES = Set.of(99991672, 99991668, 99991676, 91403, 1254302, 1254304, 1254607);
    private static final Set<Integer> RATE_CODES = Set.of(99991400, 1254290, 1254291, 11232);
    private static final Set<Integer> TOKEN_CODES = Set.of(99991661, 99991663, 99991664, 99991671);

    /** 飞书业务错误，携带飞书错误码（如 230013 = 用户不在应用可用范围内）。 */
    public static class FeishuApiException extends QdException {
        private final int feishuCode;
        public FeishuApiException(ErrorCode ec, boolean retryable, int feishuCode, String msg) {
            super(ec, retryable, msg);
            this.feishuCode = feishuCode;
        }
        public int feishuCode() { return feishuCode; }
    }

    private final QdProperties.Feishu cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration readTimeout;
    private String token;
    private long tokenExpireAtMillis;

    public FeishuHttp(QdProperties props, ObjectMapper mapper) {
        this.cfg = props.getFeishu();
        this.mapper = mapper;
        this.readTimeout = props.getHttp().getReadTimeout();
        this.http = HttpClient.newBuilder().connectTimeout(props.getHttp().getConnectTimeout()).build();
    }

    public ObjectMapper mapper() { return mapper; }

    /** 调用接口，返回响应中的 data 节点（无 data 时为空对象）。 */
    public JsonNode call(String method, String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).timeout(readTimeout)
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json; charset=utf-8");
        try {
            String json = body == null ? null : mapper.writeValueAsString(body);
            b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        } catch (IOException e) {
            throw new QdException(ErrorCode.SYS, false, "请求体序列化失败", e);
        }
        JsonNode root = send(b.build());
        int code = root.path("code").asInt(-1);
        if (code != 0) throw convert(code, root.path("msg").asText(""));
        return root.has("data") ? root.get("data") : mapper.createObjectNode();
    }

    // ---------------- 令牌 ----------------

    private synchronized String accessToken() {
        long now = System.currentTimeMillis();
        if (token != null && now < tokenExpireAtMillis) return token;
        ObjectNode req = mapper.createObjectNode().put("app_id", cfg.getAppId()).put("app_secret", cfg.getAppSecret());
        HttpRequest r;
        try {
            r = HttpRequest.newBuilder(URI.create(base() + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .timeout(readTimeout).header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req))).build();
        } catch (IOException e) {
            throw new QdException(ErrorCode.SYS, false, "令牌请求序列化失败", e);
        }
        JsonNode root;
        try {
            root = send(r);
        } catch (QdException e) {
            if (e.errorCode() == ErrorCode.FEISHU_TIMEOUT) throw e;
            throw new QdException(ErrorCode.FEISHU_TOKEN, false, "飞书访问令牌获取失败：" + e.getMessage());
        }
        if (root.path("code").asInt(-1) != 0 || !root.hasNonNull("tenant_access_token")) {
            throw new QdException(ErrorCode.FEISHU_TOKEN, false,
                    "飞书访问令牌获取失败（code=" + root.path("code").asInt(-1) + "），请检查 App ID / QD_FEISHU_APP_SECRET");
        }
        token = root.get("tenant_access_token").asText();
        // 提前 5 分钟刷新
        tokenExpireAtMillis = now + Math.max(root.path("expire").asLong(7200) - 300, 60) * 1000L;
        return token;
    }

    private synchronized void invalidateToken() { token = null; }

    // ---------------- 内部 ----------------

    private String base() { return cfg.getBaseUrl().replaceAll("/+$", ""); }

    private JsonNode send(HttpRequest req) {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QdException(ErrorCode.FEISHU_TIMEOUT, false, "等待飞书响应时被中断", e);
        } catch (IOException e) {
            throw new QdException(ErrorCode.FEISHU_TIMEOUT, true, "飞书超时或网络不可达（" + e.getClass().getSimpleName() + "）", e);
        }
        int s = resp.statusCode();
        if (s == 429) throw new FeishuApiException(ErrorCode.FEISHU_RATE_LIMIT, true, 429, "飞书接口限流（429）");
        if (s >= 500) throw new FeishuApiException(ErrorCode.FEISHU_TIMEOUT, true, s, "飞书服务端错误（HTTP " + s + "）");
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new QdException(ErrorCode.SYS, false, "飞书返回内容不是合法 JSON（HTTP " + s + "）", e);
        }
    }

    private QdException convert(int code, String msg) {
        String detail = "（飞书错误码 " + code + "：" + msg + "）";
        if (TOKEN_CODES.contains(code)) {
            invalidateToken();
            return new FeishuApiException(ErrorCode.FEISHU_TOKEN, true, code, "飞书访问令牌失效" + detail);
        }
        if (FORBIDDEN_CODES.contains(code)) {
            return new FeishuApiException(ErrorCode.FEISHU_FORBIDDEN, false, code, "飞书接口无权限，请检查应用权限或表格权限" + detail);
        }
        if (RATE_CODES.contains(code)) return new FeishuApiException(ErrorCode.FEISHU_RATE_LIMIT, true, code, "飞书接口限流" + detail);
        return new FeishuApiException(ErrorCode.SYS, false, code, "飞书接口返回错误" + detail);
    }
}
