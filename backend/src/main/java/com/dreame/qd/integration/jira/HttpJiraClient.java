package com.dreame.qd.integration.jira;

import com.dreame.qd.config.QdProperties;
import com.dreame.qd.model.ErrorCode;
import com.dreame.qd.model.Models.JiraComment;
import com.dreame.qd.model.Models.JiraIssue;
import com.dreame.qd.model.Models.JiraUser;
import com.dreame.qd.model.QdException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** EXT-J01：Jira REST API v2 + Basic Auth。日志中不出现密码与 Authorization 头（04 1.5）。 */
public class HttpJiraClient implements JiraClient {
    private static final Logger log = LoggerFactory.getLogger(HttpJiraClient.class);
    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final String FIELDS = "summary,issuetype,project,assignee,reporter,status,priority,created,updated,description,comment";

    private final QdProperties.Jira cfg;
    private final HttpClient http;
    private final java.time.Duration readTimeout;
    private final RateLimiter limiter;
    private final ObjectMapper mapper;
    private final String authHeader;

    public HttpJiraClient(QdProperties props, ObjectMapper mapper, RateLimiter limiter) {
        this.cfg = props.getJira();
        this.mapper = mapper;
        this.limiter = limiter;
        this.readTimeout = props.getHttp().getReadTimeout();
        this.http = HttpClient.newBuilder().connectTimeout(props.getHttp().getConnectTimeout()).build();
        String pair = cfg.getUsername() + ":" + cfg.getPassword();
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<JiraIssue> fetchUnfinishedIssues() {
        List<JiraIssue> out = new ArrayList<>();
        int start = 0;
        int total = Integer.MAX_VALUE;
        int skipped = 0;
        while (start < total) {
            String url = base() + "/rest/api/2/search?jql=" + enc(cfg.getBoardJql()) + "&fields=" + FIELDS
                    + "&expand=renderedFields&startAt=" + start + "&maxResults=" + cfg.getPageSize();
            JsonNode root = get(url);
            JsonNode issues = root.get("issues");
            if (issues == null || !issues.isArray() || !root.hasNonNull("total")) {
                throw new QdException(ErrorCode.DATA_MISSING, false, "Jira 查询结果结构异常（缺少 issues / total）");
            }
            total = root.get("total").asInt();
            for (JsonNode n : issues) {
                JiraIssue issue = parseIssue(n);
                if (issue == null) skipped++; else out.add(issue);
            }
            if (issues.isEmpty()) break; // 防止服务端异常时死循环
            start += issues.size();
        }
        if (skipped > 0) log.warn("{}：{} 条 Jira Issue 缺少必需字段，已跳过", ErrorCode.DATA_MISSING.code(), skipped);
        log.info("Jira 查询完成：共 {} 条（跳过 {} 条）", out.size(), skipped);
        return out;
    }

    @Override
    public Optional<JiraUser> findUser(String account) {
        String url = base() + "/rest/api/2/user?username=" + enc(account);
        try {
            JsonNode n = get(url);
            return Optional.of(new JiraUser(text(n, "name"), text(n, "displayName"), text(n, "emailAddress")));
        } catch (QdException e) {
            if (e.errorCode() == ErrorCode.SYS && e.getMessage().contains("HTTP 404")) return Optional.empty();
            throw e;
        }
    }

    // ---------------- 解析 ----------------

    JiraIssue parseIssue(JsonNode n) {
        try {
            JsonNode f = n.get("fields");
            String key = text(n, "key");
            String type = f == null ? null : text(f.path("issuetype"), "name");
            String status = f == null ? null : text(f.path("status"), "name");
            String cat = f == null ? null : text(f.path("status").path("statusCategory"), "key");
            String created = f == null ? null : text(f, "created");
            String updated = f == null ? null : text(f, "updated");
            if (key == null || type == null || status == null || cat == null || created == null || updated == null) {
                log.warn("{}：Issue {} 缺少必需字段", ErrorCode.DATA_MISSING.code(), key);
                return null;
            }
            return new JiraIssue(key, text(f, "summary") == null ? "" : text(f, "summary"), type,
                    text(f.path("project"), "key"), user(f.path("assignee")), user(f.path("reporter")), status, cat,
                    text(f.path("priority"), "name"), parseTime(created), parseTime(updated),
                    description(n), comments(n));
        } catch (RuntimeException e) {
            log.warn("{}：Issue {} 解析失败：{}", ErrorCode.DATA_MISSING.code(), text(n, "key"), e.getClass().getSimpleName());
            return null;
        }
    }

    /** 描述：优先用渲染后的 HTML 转纯文本；没有渲染结果时退回原始文本。描述 / 评论解析失败不影响该 Issue 入表。 */
    private static String description(JsonNode issue) {
        try {
            String html = text(issue.path("renderedFields"), "description");
            if (html != null) return HtmlText.toText(html);
            String raw = text(issue.path("fields"), "description");
            return raw == null ? "" : raw;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static List<JiraComment> comments(JsonNode issue) {
        List<JiraComment> out = new ArrayList<>();
        try {
            JsonNode raw = issue.path("fields").path("comment").path("comments");
            JsonNode rendered = issue.path("renderedFields").path("comment").path("comments");
            for (int i = 0; i < raw.size(); i++) {
                JsonNode c = raw.get(i);
                String html = i < rendered.size() ? text(rendered.get(i), "body") : null;
                String body = html != null ? HtmlText.toText(html) : (text(c, "body") == null ? "" : text(c, "body"));
                String author = text(c.path("author"), "displayName");
                if (author == null) author = text(c.path("author"), "name");
                String created = text(c, "created");
                out.add(new JiraComment(author == null ? "未知" : author, created == null ? null : parseTime(created), body));
            }
        } catch (RuntimeException e) {
            log.warn("Issue {} 的评论解析失败：{}", text(issue, "key"), e.getClass().getSimpleName());
        }
        return out;
    }

    static Instant parseTime(String s) {
        return OffsetDateTime.parse(s, JIRA_TIME).toInstant();
    }

    private static JiraUser user(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String name = text(n, "name");
        return name == null ? null : new JiraUser(name, text(n, "displayName"), text(n, "emailAddress"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return (v == null || v.isNull() || v.asText().isBlank()) ? null : v.asText();
    }

    // ---------------- HTTP ----------------

    private String base() { return cfg.getBaseUrl().replaceAll("/+$", ""); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private JsonNode get(String url) {
        limiter.acquire();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout)
                .header("Authorization", authHeader).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QdException(ErrorCode.JIRA_TIMEOUT, false, "等待 Jira 响应时被中断", e);
        } catch (IOException e) {
            throw new QdException(ErrorCode.JIRA_TIMEOUT, true, "Jira 超时或网络不可达（" + e.getClass().getSimpleName() + "）", e);
        }
        int s = resp.statusCode();
        if (s == 200) {
            try {
                return mapper.readTree(resp.body());
            } catch (IOException e) {
                throw new QdException(ErrorCode.DATA_MISSING, false, "Jira 返回内容不是合法 JSON", e);
            }
        }
        String denied = resp.headers().firstValue("X-Authentication-Denied-Reason").orElse("");
        if (s == 401) {
            throw new QdException(ErrorCode.JIRA_AUTH, false, "Jira 认证失败（401），可能是密码已修改或账号被锁定，请检查");
        }
        if (s == 403) {
            throw new QdException(ErrorCode.JIRA_FORBIDDEN, false, denied.contains("CAPTCHA")
                    ? "Jira 要求验证码（CAPTCHA_CHALLENGE，登录失败次数过多），请联系 Jira 管理员重置账号登录失败次数"
                    : "Jira 拒绝访问（403），请检查账号权限");
        }
        if (s == 429) throw new QdException(ErrorCode.JIRA_RATE_LIMIT, true, "Jira 限流（429）");
        if (s >= 500) throw new QdException(ErrorCode.JIRA_SERVER, true, "Jira 服务端错误（HTTP " + s + "）");
        throw new QdException(ErrorCode.SYS, false, "Jira 返回未预期状态（HTTP " + s + "）");
    }
}
