package com.dreame.qd.integration.jira;

import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * 把 Jira 渲染后的 HTML（renderedFields）转成纯文本，用于主表格「描述」「评论」：
 * 保留换行与列表；@人名显示为姓名；图片显示为【图片：文件名】；外部链接在文字后附地址。
 */
public final class HtmlText {
    private static final Set<String> BLOCK = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table",
            "tr", "pre", "blockquote", "hr");

    private HtmlText() {}

    public static String toText(String html) {
        if (html == null || html.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override public void head(Node node, int depth) {
                if (node instanceof TextNode t) {
                    boolean inPre = t.parent() instanceof Element p && p.closest("pre") != null;
                    String s = inPre ? t.getWholeText() : t.getWholeText().replaceAll("\\s+", " ");
                    if (!inPre && (sb.length() == 0 || sb.charAt(sb.length() - 1) == '\n')) s = s.stripLeading();
                    sb.append(s);
                } else if (node instanceof Element e) {
                    String tag = e.normalName();
                    if (tag.equals("br")) sb.append('\n');
                    else if (tag.equals("li")) { newline(sb); sb.append("• "); }
                    else if (tag.equals("img")) sb.append("【图片：").append(imageName(e)).append('】');
                    else if (tag.equals("hr")) { newline(sb); sb.append("----"); newline(sb); }
                    else if (tag.equals("td") || tag.equals("th")) { if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append(" | "); }
                    else if (BLOCK.contains(tag)) newline(sb);
                }
            }

            @Override public void tail(Node node, int depth) {
                if (!(node instanceof Element e)) return;
                String tag = e.normalName();
                if (tag.equals("a")) {
                    String href = e.attr("href");
                    String text = e.text().trim();
                    boolean mention = e.hasClass("user-hover");
                    if (!mention && href.startsWith("http") && !href.equals(text)) sb.append(" (").append(href).append(')');
                } else if (tag.equals("li") || BLOCK.contains(tag)) newline(sb);
            }
        }, Jsoup.parseBodyFragment(html).body());
        return tidy(sb.toString());
    }

    private static String imageName(Element img) {
        String alt = img.attr("alt");
        if (!alt.isBlank()) return alt;
        String src = img.attr("src");
        int q = src.indexOf('?');
        if (q >= 0) src = src.substring(0, q);
        int slash = src.lastIndexOf('/');
        return slash >= 0 ? src.substring(slash + 1) : src;
    }

    private static void newline(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
    }

    private static String tidy(String s) {
        String[] lines = s.replace((char) 160, (char) 32).split("\n", -1);
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String l = line.stripTrailing();
            if (l.isBlank()) { if (++blank > 1) continue; out.append('\n'); }
            else { blank = 0; out.append(l).append('\n'); }
        }
        return out.toString().strip();
    }
}
