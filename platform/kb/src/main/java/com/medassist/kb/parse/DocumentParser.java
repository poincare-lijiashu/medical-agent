package com.medassist.kb.parse;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档解析：把 HTML / Markdown / 纯文本统一清洗成"可切片、可引用"的规范化正文。
 *
 * <p>为什么在**写入时**解析而不是检索时清洗：
 * <ul>
 *   <li>切片偏移 {@code charStart/charEnd} 必须相对同一份正文，否则前端高亮会错位；</li>
 *   <li>去重哈希基于解析后正文，同一篇文章的 HTML 版与纯文本版才算"同一内容"；</li>
 *   <li>检索时再清洗等于每次查询都做一遍正则，命中链路白白多花 CPU。</li>
 * </ul>
 *
 * <p>不引入第三方解析库（离线环境无法新增依赖），因此：
 * <ul>
 *   <li>HTML 只做"去脚本样式 → 块级标签转换行 → 去标签 → 实体解码 → 空白收敛"，不做 DOM 树重建；</li>
 *   <li>Markdown 只做语法糖剔除（标题井号、粗斜体、链接、图片、代码围栏、列表符号、表格分隔行）；</li>
 *   <li>PDF / Word / Excel / PPT 等二进制格式**显式拒绝**（422 并给出可执行建议），
 *       绝不把乱码写进索引造成"看起来入库成功、实际检索不到"的假象。</li>
 * </ul>
 */
public final class DocumentParser {

    /** 明确不支持的二进制扩展名：离线环境没有 OCR / Office 解析库。 */
    private static final Set<String> UNSUPPORTED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp",
            "zip", "rar", "7z", "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff");

    private static final Map<String, String> HTML_EXTENSIONS = Map.of("html", "HTML", "htm", "HTML", "xhtml", "HTML");
    private static final Map<String, String> MARKDOWN_EXTENSIONS = Map.of("md", "MARKDOWN", "markdown", "MARKDOWN");

    /** 脚本/样式/模板整块丢弃（含内容），否则 CSS 选择器会污染检索词元。 */
    private static final Pattern BLOCK_DROP = Pattern.compile(
            "(?is)<(script|style|noscript|template|svg)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    /** 块级标签转成换行，保证段落边界不被粘成一句。 */
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?i)</?(p|div|br|li|ul|ol|dl|dt|dd|tr|td|th|table|thead|tbody|h[1-6]|section|article"
                    + "|header|footer|aside|nav|main|blockquote|pre|hr|figure|figcaption)\\b[^>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private static final Pattern MD_FENCE = Pattern.compile("(?m)^\\s{0,3}(```|~~~).*$");
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern MD_INLINE_CODE = Pattern.compile("`([^`\\n]*)`");
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__");
    private static final Pattern MD_ITALIC = Pattern.compile("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])");
    private static final Pattern MD_LIST_MARKER = Pattern.compile("(?m)^\\s{0,3}([-*+]|\\d{1,3}[.)])\\s+");
    private static final Pattern MD_QUOTE = Pattern.compile("(?m)^\\s{0,3}>\\s?");
    private static final Pattern MD_TABLE_SEPARATOR = Pattern.compile(
            "(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    private static final Map<String, String> ENTITIES = entities();

    private DocumentParser() {
    }

    /**
     * 解析文档。
     *
     * @param sourceType 来源类型（MANUAL / UPLOAD / URL / IMPORT / HTML / MARKDOWN / TEXT，可空）
     * @param sourceUri  原始地址或文件名（用于扩展名判定，可空）
     * @param raw        原始正文
     * @return 解析结果（正文已规范化；notes 为中文告警，入库后可查）
     */
    public static Parsed parse(String sourceType, String sourceUri, String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文档正文不能为空");
        }
        String extension = extensionOf(sourceUri);
        if (extension != null && UNSUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.KB_PARSE_UNSUPPORTED,
                    "暂不支持 ." + extension + " 二进制格式解析（离线环境未集成 OCR/Office 解析库）："
                            + "请先转为纯文本或 Markdown 后入库");
        }
        if (looksBinary(raw)) {
            throw new BusinessException(ErrorCode.KB_PARSE_UNSUPPORTED,
                    "正文包含二进制内容，无法按文本解析：请上传文本、HTML 或 Markdown");
        }

        String kind = resolveKind(sourceType, extension, raw);
        List<String> notes = new ArrayList<>(2);
        String normalized = switch (kind) {
            case "HTML" -> {
                String text = htmlToText(raw);
                notes.add("已按 HTML 解析：去除脚本/样式/标签并解码实体");
                yield text;
            }
            case "MARKDOWN" -> {
                String text = markdownToText(raw);
                notes.add("已按 Markdown 解析：去除语法标记，保留正文与代码文本");
                yield text;
            }
            default -> normalizeNewlines(raw);
        };
        String cleaned = collapseBlankLines(normalized);
        if (cleaned.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "解析后没有可用的文本内容（" + kind + "），请检查正文是否只有标签或脚本");
        }
        return new Parsed(kind, cleaned, List.copyOf(notes), raw.length());
    }

    /** 解析结果。{@code kind} 为实际生效的解析方式（PLAIN / HTML / MARKDOWN）。 */
    public record Parsed(String kind, String content, List<String> notes, int rawChars) {

        /** 落库用的告警文本（始终带上实际解析方式，便于运营核对入库质量）。 */
        public String notesText() {
            String base = "解析方式=" + kind;
            return notes.isEmpty() ? base : base + "；" + String.join("；", notes);
        }
    }

    // ---------------------------------------------------------------- 判定

    static String resolveKind(String sourceType, String extension, String raw) {
        String type = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "HTML":
                return "HTML";
            case "MARKDOWN":
                return "MARKDOWN";
            case "TEXT", "MANUAL", "IMPORT":
                // 显式声明为文本仍做一次探测：现实中大量"纯文本"其实是粘贴过来的网页
                break;
            case "URL":
                break;
            default:
                break;
        }
        if (extension != null) {
            if (HTML_EXTENSIONS.containsKey(extension)) {
                return "HTML";
            }
            if (MARKDOWN_EXTENSIONS.containsKey(extension)) {
                return "MARKDOWN";
            }
        }
        if (looksLikeHtml(raw)) {
            return "HTML";
        }
        if (looksLikeMarkdown(raw)) {
            return "MARKDOWN";
        }
        return "PLAIN";
    }

    /** HTML 特征：出现文档级标签，或标签数量 ≥ 3 且形如 {@code <tag>}。 */
    static boolean looksLikeHtml(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<body") || lower.contains("<br/>")
                || lower.contains("<br />") || lower.contains("<p>") || lower.contains("</p>")
                || lower.contains("<div") || lower.contains("<table")) {
            return true;
        }
        Matcher matcher = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]{0,10})(\\s[^>]*)?/?>").matcher(raw);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count >= 3) {
                return true;
            }
        }
        return false;
    }

    /** Markdown 特征：标题 + 围栏/表格/强调中至少两类信号，避免把正文里的 # 误判。 */
    static boolean looksLikeMarkdown(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        int signals = 0;
        if (Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+\\S").matcher(raw).find()) {
            signals += 2;
        }
        if (raw.contains("```") || raw.contains("~~~")) {
            signals += 2;
        }
        if (Pattern.compile("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*\\|").matcher(raw).find()) {
            signals++;
        }
        if (raw.contains("**") || raw.contains("](")) {
            signals++;
        }
        return signals >= 2;
    }

    /** 二进制探测：NUL 或控制字符占比过高。 */
    static boolean looksBinary(String raw) {
        if (raw.indexOf('\0') >= 0) {
            return true;
        }
        int control = 0;
        int total = Math.min(raw.length(), 4096);
        for (int i = 0; i < total; i++) {
            char c = raw.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                control++;
            }
        }
        return total > 0 && control * 100 / total > 2;
    }

    // ---------------------------------------------------------------- HTML

    static String htmlToText(String raw) {
        String text = raw;
        text = BLOCK_DROP.matcher(text).replaceAll("\n");
        text = HTML_COMMENT.matcher(text).replaceAll("\n");
        text = BLOCK_TAG.matcher(text).replaceAll("\n");
        text = ANY_TAG.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        return tidy(text);
    }

    static String decodeEntities(String text) {
        String out = text;
        for (Map.Entry<String, String> entry : ENTITIES.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue());
        }
        Matcher matcher = NUMERIC_ENTITY.matcher(out);
        StringBuilder sb = new StringBuilder(out.length());
        while (matcher.find()) {
            String replacement = matcher.group(0);
            try {
                int codePoint = matcher.group(1).isEmpty()
                        ? Integer.parseInt(matcher.group(2))
                        : Integer.parseInt(matcher.group(2), 16);
                if (Character.isValidCodePoint(codePoint) && codePoint > 0) {
                    replacement = new String(Character.toChars(codePoint));
                }
            } catch (NumberFormatException ignored) {
                // 超长数字实体：保留原样，不阻断入库
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- Markdown

    static String markdownToText(String raw) {
        String text = raw;
        text = MD_FENCE.matcher(text).replaceAll("");
        text = MD_TABLE_SEPARATOR.matcher(text).replaceAll("");
        text = MD_HEADING.matcher(text).replaceAll("");
        text = MD_QUOTE.matcher(text).replaceAll("");
        text = MD_LIST_MARKER.matcher(text).replaceAll("");
        text = MD_IMAGE.matcher(text).replaceAll("$1");
        text = MD_LINK.matcher(text).replaceAll("$1");
        text = MD_INLINE_CODE.matcher(text).replaceAll("$1");
        text = MD_BOLD.matcher(text).replaceAll(m -> m.group(1) != null ? m.group(1) : m.group(2));
        text = MD_ITALIC.matcher(text).replaceAll("$1");
        text = ANY_TAG.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        return tidy(text);
    }

    // ---------------------------------------------------------------- 收尾

    /** 行内折叠空白、去行首尾空白（保留段落换行）。 */
    private static String tidy(String text) {
        String[] lines = normalizeNewlines(text).split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : lines) {
            String trimmed = line.replaceAll("[ \\t\\x0B\\f]+", " ").trim();
            sb.append(trimmed).append('\n');
        }
        return sb.toString();
    }

    private static String collapseBlankLines(String text) {
        return text.replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String normalizeNewlines(String raw) {
        return raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 提取小写扩展名（不含点）；无扩展名返回 null。 */
    static String extensionOf(String sourceUri) {
        if (sourceUri == null || sourceUri.isBlank()) {
            return null;
        }
        String value = sourceUri.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> entities() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("&nbsp;", " ");
        map.put("&ldquo;", "“");
        map.put("&rdquo;", "”");
        map.put("&lsquo;", "‘");
        map.put("&rsquo;", "’");
        map.put("&hellip;", "…");
        map.put("&mdash;", "—");
        map.put("&ndash;", "–");
        map.put("&middot;", "·");
        map.put("&times;", "×");
        map.put("&divide;", "÷");
        map.put("&plusmn;", "±");
        map.put("&deg;", "°");
        map.put("&micro;", "µ");
        map.put("&le;", "≤");
        map.put("&ge;", "≥");
        map.put("&ne;", "≠");
        map.put("&copy;", "©");
        map.put("&reg;", "®");
        map.put("&trade;", "™");
        map.put("&quot;", "\"");
        map.put("&apos;", "'");
        map.put("&lt;", "<");
        map.put("&gt;", ">");
        // &amp; 必须最后替换，否则 &amp;lt; 会被二次解码成 <
        map.put("&amp;", "&");
        return map;
    }
}
