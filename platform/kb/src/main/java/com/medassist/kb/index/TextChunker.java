package com.medassist.kb.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切片器：按"段落 → 句子 → 字符窗口"三级边界切分，并保留原文偏移。
 *
 * <p>为什么不用固定长度硬切：
 * <ul>
 *   <li>硬切会把一句诊断标准/用药禁忌切成两半，检索命中后人工核对还要拼回来；</li>
 *   <li>引用溯源需要精确的 {@code charStart/charEnd}，切片必须记录相对于原文的偏移；</li>
 *   <li>保留 {@code overlapChars} 重叠，避免答案恰好跨在设计边界上导致召回失败。</li>
 * </ul>
 *
 * <p>本类无 Spring 依赖、无 IO，纯函数，便于单元测试与回归。
 */
public final class TextChunker {

    /** 句子级边界（含中英文标点与换行）。 */
    private static final String SENTENCE_END = "。！？!?；;\n";

    private TextChunker() {
    }

    /**
     * 切片。
     *
     * @param raw          原文（允许为空/空白，返回空列表）
     * @param maxChars     单块最大字符数
     * @param overlapChars 相邻块重叠字符数（自动收敛到 maxChars/2 以内）
     * @return 有序切片；seq 从 0 开始；start/end 相对规范化后的原文
     */
    public static List<Piece> split(String raw, int maxChars, int overlapChars) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        int max = Math.max(50, maxChars);
        int overlap = Math.max(0, Math.min(overlapChars, max / 2));
        String text = normalize(raw);
        List<Piece> pieces = new ArrayList<>();
        int pos = 0;
        int seq = 0;
        while (pos < text.length()) {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (pos >= text.length()) {
                break;
            }
            int limit = Math.min(text.length(), pos + max);
            int end = limit;
            if (limit < text.length()) {
                int cut = lastSentenceBoundary(text, pos + Math.max(1, max / 2), limit);
                if (cut > pos) {
                    end = cut;
                }
            }
            int stop = end;
            while (stop > pos && Character.isWhitespace(text.charAt(stop - 1))) {
                stop--;
            }
            if (stop > pos) {
                pieces.add(new Piece(seq++, text.substring(pos, stop), pos, stop, sectionOf(text, pos)));
            }
            if (end >= text.length()) {
                break;
            }
            int next = end - overlap;
            pos = next <= pos ? end : next;
        }
        return pieces;
    }

    /** 统一换行，保证偏移在存储与回显时一致。 */
    static String normalize(String raw) {
        return raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 从 to 往前找最近的句子边界（返回边界之后的位置）。 */
    private static int lastSentenceBoundary(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (SENTENCE_END.indexOf(c) >= 0) {
                return i + 1;
            }
            if (c == '.' && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 章节标题推断：取切片前最近一行的短文本（≤40 字符）。
     *
     * <p>不解析 Markdown 语法，避免把用户正文里的 # 误当标题；标题只是给前端的展示提示，
     * 真正的引用溯源靠 charStart/charEnd。
     */
    private static String sectionOf(String text, int start) {
        int lineStart = start;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int prevEnd = lineStart - 1;
        if (prevEnd <= 0) {
            return null;
        }
        int prevStart = prevEnd;
        while (prevStart > 0 && text.charAt(prevStart - 1) != '\n') {
            prevStart--;
        }
        String line = text.substring(prevStart, prevEnd).trim();
        if (line.isEmpty() || line.length() > 40) {
            return null;
        }
        return line;
    }

    /** 一个切片：content 与原文 [start, end) 完全对应。 */
    public record Piece(int seq, String content, int start, int end, String section) {
    }
}
