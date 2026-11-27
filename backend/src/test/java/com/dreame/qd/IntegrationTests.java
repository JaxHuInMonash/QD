package com.dreame.qd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dreame.qd.config.QdProperties;
import com.dreame.qd.integration.feishu.AdminConfigRepository;
import com.dreame.qd.integration.feishu.BitableClient;
import com.dreame.qd.integration.feishu.BitableTaskTableGateway;
import com.dreame.qd.integration.feishu.FeishuContactGateway;
import com.dreame.qd.integration.feishu.FeishuHttp;
import com.dreame.qd.integration.feishu.FeishuMessageGateway;
import com.dreame.qd.integration.jira.HttpJiraClient;
import com.dreame.qd.integration.jira.RateLimiter;
import com.dreame.qd.model.ErrorCode;
import com.dreame.qd.model.Models.JiraIssue;
import com.dreame.qd.model.Models.StatusCategory;
import com.dreame.qd.model.Models.TaskRow;
import com.dreame.qd.model.Models.TaskType;
import com.dreame.qd.model.QdException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 集成层测试：用本机 HTTP 服务模拟 Jira 与飞书开放平台。 */
class IntegrationTests {
    final ObjectMapper mapper = new ObjectMapper();
    HttpServer server;
    String base;
    /** 记录收到的请求："METHOD path?query" 与请求体、Authorization 头。 */
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> auths = new CopyOnWriteArrayList<>();
    volatile Function<String, Resp> handler = path -> new Resp(404, "{}");

    record Resp(int status, String body, String... headers) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            requests.add(ex.getRequestMethod() + " " + path);
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auths.add(ex.getRequestHeaders().getFirst("Authorization"));
            Resp r = handler.apply(path);
            for (int i = 0; i + 1 < r.headers().length; i += 2) ex.getResponseHeaders().add(r.headers()[i], r.headers()[i + 1]);
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    QdProperties props() {
        QdProperties p = SupportTests.complete();
        p.getJira().setBaseUrl(base);
        p.getJira().setUsername("huhk");
        p.getJira().setPassword("s3cret-pass");
        p.getJira().setPageSize(2);
        p.getFeishu().setBaseUrl(base);
        p.getHttp().setReadTimeout(Duration.ofSeconds(2));
        return p;
    }

    // ================= Jira（EXT-J01） =================

    static String issueJson(String key, String type, String cat) {
        return "{\"key\":\"" + key + "\",\"fields\":{\"summary\":\"标题 " + key + "\",\"issuetype\":{\"name\":\"" + type + "\"},"
                + "\"project\":{\"key\":\"" + key.split("-")[0] + "\"},\"assignee\":{\"name\":\"huhk\",\"displayName\":\"胡桓康\",\"emailAddress\":\"huhk@dreame.tech\"},"
                + "\"reporter\":null,\"status\":{\"name\":\"开发中\",\"statusCategory\":{\"key\":\"" + cat + "\"}},\"priority\":{\"name\":\"高\"},"
                + "\"created\":\"2026-10-08T10:12:00.000+0800\",\"updated\":\"2026-11-24T18:30:00.000+0800\"}}";
    }

    HttpJiraClient jira() { return new HttpJiraClient(props(), mapper, new RateLimiter(90, 60_000)); }

    @Test
    void jiraPaginatesParsesAndSkipsBadIssues() {
        handler = path -> {
            if (path.contains("startAt=0")) return new Resp(200, "{\"total\":3,\"issues\":[" + issueJson("VERA-1", "Story", "new") + ",{\"key\":\"BAD-1\",\"fields\":{}}]}");
            if (path.contains("startAt=2")) return new Resp(200, "{\"total\":3,\"issues\":[" + issueJson("BUGA-2", "Bug（软件测试）", "indeterminate") + "]}");
            return new Resp(500, "{}");
        };
        List<JiraIssue> issues = jira().fetchUnfinishedIssues();
        assertThat(issues).extracting(JiraIssue::key).containsExactly("VERA-1", "BUGA-2");   // BAD-1 缺必需字段被跳过
        JiraIssue i = issues.get(0);
        assertThat(i.assignee().email()).isEqualTo("huhk@dreame.tech");
        assertThat(i.reporter()).isNull();
        assertThat(i.created()).isEqualTo(Instant.parse("2026-10-08T02:12:00Z"));
        assertThat(requests.get(0)).contains("/rest/api/2/search?jql=").contains("maxResults=2").contains("fields=summary");
        // Basic Auth 头正确，且不会出现在请求路径里
        assertThat(auths.get(0)).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("huhk:s3cret-pass".getBytes(StandardCharsets.UTF_8)));
        assertThat(requests.toString()).doesNotContain("s3cret-pass");
    }

    @Test
    void jiraReadsRenderedDescriptionAndComments() {
        String issue = "{\"key\":\"VERA-9\",\"fields\":{\"summary\":\"s\",\"issuetype\":{\"name\":\"Story\"},\"project\":{\"key\":\"VERA\"},"
                + "\"assignee\":null,\"reporter\":null,\"status\":{\"name\":\"开发中\",\"statusCategory\":{\"key\":\"new\"}},\"priority\":null,"
                + "\"created\":\"2026-10-08T10:12:00.000+0800\",\"updated\":\"2026-11-24T18:30:00.000+0800\","
                + "\"description\":\"h2. 原始标记\","
                + "\"comment\":{\"comments\":[{\"author\":{\"name\":\"a\",\"displayName\":\"甲\"},\"created\":\"2026-11-20T10:05:00.000+0800\",\"body\":\"*raw1*\"},"
                + "{\"author\":{\"name\":\"b\"},\"created\":\"2026-11-21T10:05:00.000+0800\",\"body\":\"raw2\"}]}},"
                + "\"renderedFields\":{\"description\":\"<p>渲染描述</p>\",\"comment\":{\"comments\":[{\"body\":\"<p><b>评论一</b></p>\"}]}}}";
        handler = path -> new Resp(200, "{\"total\":1,\"issues\":[" + issue + "]}");
        JiraIssue i = jira().fetchUnfinishedIssues().get(0);
        assertThat(i.description()).isEqualTo("渲染描述");
        assertThat(i.comments()).hasSize(2);
        assertThat(i.comments().get(0).author()).isEqualTo("甲");
        assertThat(i.comments().get(0).text()).isEqualTo("评论一");                 // 渲染后的 HTML 转纯文本
        assertThat(i.comments().get(0).created()).isEqualTo(Instant.parse("2026-11-20T02:05:00Z"));
        assertThat(i.comments().get(1).author()).isEqualTo("b");                  // 无显示名退回登录名
        assertThat(i.comments().get(1).text()).isEqualTo("raw2");                 // 没有渲染结果时退回原文
        assertThat(requests.get(0)).contains("description,comment").contains("expand=renderedFields");
    }

    @Test
    void jiraMapsStatusCodesToErrorCodes() {
        record Case(int status, ErrorCode code, boolean retryable, String... headers) {}
        List<Case> cases = List.of(new Case(401, ErrorCode.JIRA_AUTH, false), new Case(403, ErrorCode.JIRA_FORBIDDEN, false),
                new Case(429, ErrorCode.JIRA_RATE_LIMIT, true), new Case(502, ErrorCode.JIRA_SERVER, true));
        for (Case c : cases) {
            handler = path -> new Resp(c.status(), "{}");
            assertThatThrownBy(() -> jira().fetchUnfinishedIssues()).isInstanceOfSatisfying(QdException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(c.code());
                assertThat(e.retryable()).isEqualTo(c.retryable());
                assertThat(e.getMessage()).doesNotContain("s3cret-pass");
            });
        }
        handler = path -> new Resp(403, "{}", "X-Authentication-Denied-Reason", "CAPTCHA_CHALLENGE; login-url=x");
        assertThatThrownBy(() -> jira().fetchUnfinishedIssues()).isInstanceOfSatisfying(QdException.class,
                e -> assertThat(e.getMessage()).contains("CAPTCHA_CHALLENGE").contains("重置"));
    }

    @Test
    void jiraStructureErrorAndNetworkError() {
        handler = path -> new Resp(200, "{\"unexpected\":true}");
        assertThatThrownBy(() -> jira().fetchUnfinishedIssues()).isInstanceOfSatisfying(QdException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DATA_MISSING));
        QdProperties p = props();
        p.getJira().setBaseUrl("http://127.0.0.1:1");           // 无服务监听
        assertThatThrownBy(() -> new HttpJiraClient(p, mapper, new RateLimiter(90, 60_000)).fetchUnfinishedIssues())
                .isInstanceOfSatisfying(QdException.class, e -> { assertThat(e.errorCode()).isEqualTo(ErrorCode.JIRA_TIMEOUT); assertThat(e.retryable()).isTrue(); });
    }

    @Test
    void jiraFindUser() {
        handler = path -> path.contains("username=zhaolei") ? new Resp(200, "{\"name\":\"zhaolei\",\"displayName\":\"赵磊\",\"emailAddress\":\"zl@dreame.tech\"}") : new Resp(404, "{}");
        assertThat(jira().findUser("zhaolei")).get().extracting(u -> u.email()).isEqualTo("zl@dreame.tech");
        assertThat(jira().findUser("nobody")).isEmpty();
    }

    // ================= 飞书（EXT-F01～F07） =================

    static final String TOKEN_OK = "{\"code\":0,\"tenant_access_token\":\"t-secret-token\",\"expire\":7200}";

    FeishuHttp feishu() { return new FeishuHttp(props(), mapper); }

    @Test
    void feishuGetsTokenOnceAndSendsBearer() {
        handler = path -> path.contains("tenant_access_token") ? new Resp(200, TOKEN_OK) : new Resp(200, "{\"code\":0,\"data\":{\"x\":1}}");
        FeishuHttp f = feishu();
        assertThat(f.call("POST", "/open-apis/foo", Map.of("a", 1)).path("x").asInt()).isEqualTo(1);
        f.call("GET", "/open-apis/foo", null);
        assertThat(requests.stream().filter(r -> r.contains("tenant_access_token")).count()).isEqualTo(1);   // 令牌缓存
        assertThat(auths.get(1)).isEqualTo("Bearer t-secret-token");
        assertThat(bodies.get(0)).contains("\"app_id\":\"cli_x\"");
    }

    @Test
    void feishuErrorCodeMapping() {
        record Case(int status, String body, ErrorCode code, boolean retryable) {}
        List<Case> cases = List.of(
                new Case(200, "{\"code\":99991672,\"msg\":\"no scope\"}", ErrorCode.FEISHU_FORBIDDEN, false),
                new Case(200, "{\"code\":1254302,\"msg\":\"no permission\"}", ErrorCode.FEISHU_FORBIDDEN, false),
                new Case(200, "{\"code\":99991400,\"msg\":\"rate\"}", ErrorCode.FEISHU_RATE_LIMIT, true),
                new Case(429, "{}", ErrorCode.FEISHU_RATE_LIMIT, true),
                new Case(503, "{}", ErrorCode.FEISHU_TIMEOUT, true),
                new Case(200, "{\"code\":1254043,\"msg\":\"other\"}", ErrorCode.SYS, false));
        for (Case c : cases) {
            handler = path -> path.contains("tenant_access_token") ? new Resp(200, TOKEN_OK) : new Resp(c.status(), c.body());
            assertThatThrownBy(() -> feishu().call("GET", "/open-apis/foo", null)).isInstanceOfSatisfying(QdException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(c.code());
                assertThat(e.retryable()).isEqualTo(c.retryable());
                assertThat(e.getMessage()).doesNotContain("t-secret-token");
            });
        }
    }

    @Test
    void feishuTokenFailure() {
        handler = path -> new Resp(200, "{\"code\":10003,\"msg\":\"invalid param\"}");
        assertThatThrownBy(() -> feishu().call("GET", "/open-apis/foo", null)).isInstanceOfSatisfying(QdException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FEISHU_TOKEN));
    }

    @Test
    void bitableSearchAllFollowsPagesAndGatewayParsesRows() {
        handler = path -> {
            if (path.contains("tenant_access_token")) return new Resp(200, TOKEN_OK);
            String key = path.contains("page_token=P2") ? "VERA-2" : "VERA-1";
            String more = path.contains("page_token=P2") ? "{\"has_more\":false" : "{\"has_more\":true,\"page_token\":\"P2\"";
            return new Resp(200, "{\"code\":0,\"data\":" + more + ",\"items\":[{\"record_id\":\"rec" + key + "\",\"fields\":{"
                    + "\"Issue 编号\":{\"text\":\"" + key + "\",\"link\":\"https://x/browse/" + key + "\"},"
                    + "\"标题\":[{\"text\":\"首页\",\"type\":\"text\"},{\"text\":\"改版\",\"type\":\"text\"}],"
                    + "\"类型\":\"Story\",\"所属项目\":\"VERA\",\"经办人\":[{\"id\":\"ou_a\",\"name\":\"甲\"}],\"经办人（显示）\":\"甲\","
                    + "\"状态类别\":\"进行中\",\"创建时间\":1791000000000,\"最后同步时间\":1795000000000}}]}}");
        };
        var props = props();
        var gw = new BitableTaskTableGateway(new BitableClient(feishu()), props, RuleTests.MAPPER);
        List<TaskRow> rows = gw.listAll();
        assertThat(rows).extracting(TaskRow::key).containsExactly("VERA-1", "VERA-2");
        TaskRow r = rows.get(0);
        assertThat(r.recordId()).isEqualTo("recVERA-1");
        assertThat(r.title()).isEqualTo("首页改版");
        assertThat(r.type()).isEqualTo(TaskType.STORY);
        assertThat(r.assigneeOpenId()).isEqualTo("ou_a");
        assertThat(r.category()).isEqualTo(StatusCategory.IN_PROGRESS);
        assertThat(r.syncedAt()).isEqualTo(Instant.ofEpochMilli(1795000000000L));
        assertThat(requests.stream().filter(x -> x.contains("/records/search")).count()).isEqualTo(2);
        assertThat(requests.get(1)).contains("user_id_type=open_id");
    }

    @Test
    void gatewayWritesBatchesWithDocumentedFieldFormats() throws Exception {
        handler = path -> path.contains("tenant_access_token") ? new Resp(200, TOKEN_OK) : new Resp(200, "{\"code\":0,\"data\":{}}");
        var gw = new BitableTaskTableGateway(new BitableClient(feishu()), props(), RuleTests.MAPPER);
        Instant t = Instant.parse("2026-11-26T08:50:00Z");
        TaskRow mapped = new TaskRow(null, "VERA-1", "标题", TaskType.BUG, "BUGA2506M", "ou_a", "甲", "报告人", "待提交", StatusCategory.TODO, "高", t, t, t);
        TaskRow unmapped = new TaskRow("recX", "VERA-2", "标题2", TaskType.STORY, "VERA2506M", null, "孙伟（未对应）", "朱卓", "开发中", StatusCategory.IN_PROGRESS, null, t, t, t);
        gw.createBatch(List.of(mapped));
        gw.updateBatch(List.of(unmapped));
        gw.deleteBatch(List.of("recDel1", "recDel2"));
        gw.createBatch(List.of());                                       // 空批次不发请求

        JsonNode create = mapper.readTree(bodies.get(1));                 // 0 为令牌请求
        JsonNode f = create.path("records").get(0).path("fields");
        assertThat(requests.get(1)).contains("/tables/t/records/batch_create").contains("user_id_type=open_id");
        assertThat(f.path("Issue 编号").path("text").asText()).isEqualTo("VERA-1");
        assertThat(f.path("Issue 编号").path("link").asText()).isEqualTo("https://jira.example.com/browse/VERA-1");
        assertThat(f.path("类型").asText()).isEqualTo("Bug");
        assertThat(f.path("经办人").get(0).path("id").asText()).isEqualTo("ou_a");
        assertThat(f.path("状态类别").asText()).isEqualTo("待办");
        assertThat(f.path("创建时间").asLong()).isEqualTo(t.toEpochMilli());
        assertThat(f.size()).isEqualTo(15);                               // 13 个原字段 + 描述 + 评论，不多不少
        assertThat(f.path("描述").asText()).isEmpty();                    // 无描述写空串（更新时用于清空）
        assertThat(f.path("评论").asText()).isEmpty();

        JsonNode update = mapper.readTree(bodies.get(2)).path("records").get(0);
        assertThat(update.path("record_id").asText()).isEqualTo("recX");
        assertThat(update.path("fields").path("经办人").isArray()).isTrue();      // 对应不上 → 清空人员字段
        assertThat(update.path("fields").path("经办人").size()).isZero();
        assertThat(update.path("fields").path("经办人（显示）").asText()).isEqualTo("孙伟（未对应）");
        assertThat(mapper.readTree(bodies.get(3)).path("records").toString()).isEqualTo("[\"recDel1\",\"recDel2\"]");
        assertThat(requests).hasSize(4);
    }

    @Test
    void adminConfigIsCleanedPerSpec() {
        long jan1 = Instant.parse("2026-12-31T16:00:00Z").toEpochMilli();      // 北京时间 2027-01-01 00:00
        handler = path -> {
            if (path.contains("tenant_access_token")) return new Resp(200, TOKEN_OK);
            if (path.contains("/tables/t1/")) return new Resp(200, "{\"code\":0,\"data\":{\"has_more\":false,\"items\":[{\"record_id\":\"1\",\"fields\":{\"Jira 账号\":[{\"text\":\" ZhaoLei \"}]}},"
                    + "{\"record_id\":\"2\",\"fields\":{\"姓名\":\"没有账号\"}}]}}");
            if (path.contains("/tables/t2/")) return new Resp(200, "{\"code\":0,\"data\":{\"has_more\":false,\"items\":[{\"record_id\":\"1\",\"fields\":{\"Jira 账号\":\"chenchen\",\"飞书人员\":[{\"id\":\"ou_c\"}]}},"
                    + "{\"record_id\":\"2\",\"fields\":{\"Jira 账号\":\"chenchen\",\"飞书人员\":[{\"id\":\"ou_other\"}]}}]}}");
            return new Resp(200, "{\"code\":0,\"data\":{\"has_more\":false,\"items\":[{\"record_id\":\"1\",\"fields\":{\"日期\":" + jan1 + ",\"类型\":\"调休补班\"}},"
                    + "{\"record_id\":\"2\",\"fields\":{\"日期\":" + jan1 + ",\"类型\":\"法定节假日\",\"名称\":\"元旦\"}},"
                    + "{\"record_id\":\"3\",\"fields\":{\"日期\":" + jan1 + "}}]}}");
        };
        var cfg = new AdminConfigRepository(new BitableClient(feishu()), props()).load();
        assertThat(cfg.excludes()).containsExactly("zhaolei");                         // 小写、去空格、空账号忽略
        assertThat(cfg.mappings()).containsEntry("chenchen", "ou_c").hasSize(1);       // 多行取第一行
        assertThat(cfg.holidays()).hasSize(1);
        assertThat(cfg.holidays().get(0).makeup()).isFalse();                          // 冲突以法定节假日为准
        assertThat(cfg.holidays().get(0).date()).isEqualTo(java.time.LocalDate.of(2027, 1, 1));
    }

    @Test
    void contactLookupBatchesByEmail() {
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < 51; i++) emails.add("u" + i + "@dreame.tech");
        handler = path -> path.contains("tenant_access_token") ? new Resp(200, TOKEN_OK)
                : new Resp(200, "{\"code\":0,\"data\":{\"user_list\":[{\"email\":\"U0@dreame.tech\",\"user_id\":\"ou_0\"},{\"email\":\"nobody@dreame.tech\"}]}}");
        var out = new FeishuContactGateway(feishu()).findOpenIdsByEmail(emails);
        assertThat(out).containsEntry("u0@dreame.tech", "ou_0").hasSize(1);            // 查不到的邮箱不返回
        assertThat(requests.stream().filter(r -> r.contains("batch_get_id")).count()).isEqualTo(2);   // 51 个邮箱分 2 批（≤50）
    }

    @Test
    void messageGatewayErrorMapping() {
        handler = path -> path.contains("tenant_access_token") ? new Resp(200, TOKEN_OK) : new Resp(200, "{\"code\":230013,\"msg\":\"Bot has NO availability to this user.\"}");
        assertThatThrownBy(() -> new FeishuMessageGateway(feishu()).sendCard("ou_x", "{}", "u1")).isInstanceOfSatisfying(QdException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.FEISHU_SEND);
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).contains("可用范围");
        });
        handler = path -> path.contains("tenant_access_token") ? new Resp(200, TOKEN_OK) : new Resp(503, "{}");
        assertThatThrownBy(() -> new FeishuMessageGateway(feishu()).sendCard("ou_x", "{}", "u1")).isInstanceOfSatisfying(QdException.class,
                e -> assertThat(e.retryable()).isTrue());
        handler = path -> path.contains("tenant_access_token") ? new Resp(200, TOKEN_OK) : new Resp(200, "{\"code\":0,\"data\":{\"message_id\":\"om_1\"}}");
        new FeishuMessageGateway(feishu()).sendCard("ou_x", "{\"schema\":\"2.0\"}", "uuid-1");
        JsonNode body = null;
        try { body = mapper.readTree(bodies.get(bodies.size() - 1)); } catch (IOException e) { throw new IllegalStateException(e); }
        assertThat(body.path("receive_id").asText()).isEqualTo("ou_x");
        assertThat(body.path("msg_type").asText()).isEqualTo("interactive");
        assertThat(body.path("uuid").asText()).isEqualTo("uuid-1");
        assertThat(requests.get(requests.size() - 1)).contains("receive_id_type=open_id");
    }
}
