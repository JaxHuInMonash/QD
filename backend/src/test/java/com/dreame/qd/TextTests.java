package com.dreame.qd;

import static org.assertj.core.api.Assertions.assertThat;

import com.dreame.qd.integration.jira.HtmlText;
import com.dreame.qd.model.Models.JiraComment;
import com.dreame.qd.rule.TaskMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 描述 / 评论：HTML 转纯文本与单元格内容格式。 */
class TextTests {

    @Test
    void htmlKeepsParagraphsListsMentionsImagesAndLinks() {
        String html = "<p>第一段 <b>加粗</b></p><p>第二段<br/>换行</p>"
                + "<ul><li>要点一</li><li>要点二</li></ul>"
                + "<p><a class=\"user-hover\" href=\"/secure/ViewProfile.jspa?name=zhuzhuo\">朱卓</a> 请看 "
                + "<a href=\"https://wiki.example.com/x\">方案文档</a></p>"
                + "<span class=\"image-wrap\"><img src=\"/secure/attachment/123/shot.png?x=1\" alt=\"shot.png\"/></span>";
        String text = HtmlText.toText(html);
        assertThat(text).isEqualTo("第一段 加粗\n第二段\n换行\n• 要点一\n• 要点二\n朱卓 请看 方案文档 (https://wiki.example.com/x)\n【图片：shot.png】");
    }

    @Test
    void htmlHandlesPreBlankAndEntities() {
        assertThat(HtmlText.toText(null)).isEmpty();
        assertThat(HtmlText.toText("  ")).isEmpty();
        assertThat(HtmlText.toText("<pre>a\n  b</pre>")).isEqualTo("a\n  b");
        assertThat(HtmlText.toText("<p>1 &lt; 2 &amp;&nbsp;3</p>")).isEqualTo("1 < 2 & 3");
        assertThat(HtmlText.toText("<p>a</p><p></p><p></p><p>b</p>")).isEqualTo("a\nb");
    }

    @Test
    void commentsAreChronologicalWithAuthorAndBeijingTime() {
        var c1 = new JiraComment("胡桓康", Instant.parse("2026-11-20T02:05:00Z"), "第一条");
        var c2 = new JiraComment("朱卓", Instant.parse("2026-11-21T10:30:00Z"), "第二条\n多行");
        assertThat(TaskMapper.formatComments(List.of(c1, c2)))
                .isEqualTo("【胡桓康 2026-11-20 10:05】\n第一条\n\n【朱卓 2026-11-21 18:30】\n第二条\n多行");
        assertThat(TaskMapper.formatComments(List.of())).isEmpty();
        assertThat(TaskMapper.formatComments(null)).isEmpty();
    }

    @Test
    void tooManyCommentsKeepNewestAndSayHowManyWereDropped() {
        List<JiraComment> list = new ArrayList<>();
        String body = "x".repeat(9_000);                         // 单条不超过 1 万字
        for (int i = 1; i <= 8; i++) list.add(new JiraComment("作者" + i, Instant.parse("2026-11-2" + (i % 9) + "T02:00:00Z"), body));
        String s = TaskMapper.formatComments(list);
        assertThat(s.length()).isLessThanOrEqualTo(TaskMapper.MAX_COMMENTS + 100);
        assertThat(s).startsWith("…（已省略较早的 ").contains("作者8").doesNotContain("作者1】");
    }

    @Test
    void hugeSingleCommentAndDescriptionAreTruncated() {
        var c = new JiraComment("甲", Instant.parse("2026-11-20T02:05:00Z"), "y".repeat(30_000));
        assertThat(TaskMapper.formatComments(List.of(c))).contains("本条过长，已截断").hasSizeLessThan(10_100);
        String d = TaskMapper.limitDescription("z".repeat(25_000));
        assertThat(d).endsWith("…（内容过长，已截断）").hasSizeLessThan(20_100);
        assertThat(TaskMapper.limitDescription("短描述")).isEqualTo("短描述");
        assertThat(TaskMapper.limitDescription(null)).isEmpty();
    }
}
