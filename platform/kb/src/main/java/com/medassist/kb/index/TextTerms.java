package com.medassist.kb.index;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 中文可检索词元生成（bigram）与 tsquery 构造。
 *
 * <p>为什么必须自己做：PostgreSQL 内置分词器没有中文词典，{@code to_tsvector('simple', '急性阑尾炎')}
 * 会把整句当作一个词元，查询"阑尾炎"永远匹配不到。业界通用的低成本方案是**二元组（bigram）索引**：
 * <pre>
 *   入库：急性阑尾炎 → "急性 性阑 阑尾 尾炎"
 *   查询：阑尾炎     → "阑尾 尾炎"  → tsquery: 阑尾 | 尾炎
 * </pre>
 * 中文二元组召回率接近分词器，且不依赖任何扩展；英文/数字按下词切分并小写化。
 *
 * <p>词元只保留字母/数字/汉字，天然排除 tsquery 的操作符（&amp; | ! : *），不存在查询注入。
 */
public final class TextTerms {

    /** 单次查询最多保留的词元数：防止超长 query 生成巨型 tsquery 拖垮规划器。 */
    private static final int MAX_QUERY_TERMS = 64;

    private TextTerms() {
    }

    /** 入库用：返回空格分隔的词元串（同一 chunk 内去重并保序）。 */
    public static String toTerms(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        return String.join(" ", tokens(text));
    }

    /**
     * 查询用：返回 {@code to_tsquery('simple', ...)} 的第二参数。
     *
     * <p>多词元之间用 OR（{@code |}）：二元组全部 AND 会把"阑尾炎伴穿孔"这种长查询搜成空集，
     * 排序交给 ts_rank / RRF 处理。
     *
     * @return 空串表示查询中没有可检索字符
     */
    public static String toTsQuery(String query) {
        Set<String> terms = tokens(query);
        if (terms.isEmpty()) {
            return "";
        }
        return terms.stream().limit(MAX_QUERY_TERMS).reduce((a, b) -> a + " | " + b).orElse("");
    }

    /** 文本 → 去重词元（保序）。 */
    static Set<String> tokens(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                flushWord(word, out);
                cjk.appendCodePoint(cp);
            } else if (Character.isLetterOrDigit(cp)) {
                flushCjk(cjk, out);
                word.appendCodePoint(Character.toLowerCase(cp));
            } else {
                flushWord(word, out);
                flushCjk(cjk, out);
            }
        }
        flushWord(word, out);
        flushCjk(cjk, out);
        return out;
    }

    private static void flushCjk(StringBuilder run, Set<String> out) {
        int len = run.length();
        if (len == 0) {
            return;
        }
        if (len == 1) {
            out.add(run.toString());
        } else {
            for (int i = 0; i + 1 < len; i++) {
                out.add(run.substring(i, i + 2));
            }
        }
        run.setLength(0);
    }

    private static void flushWord(StringBuilder word, Set<String> out) {
        if (word.length() >= 2) {
            out.add(word.toString());
        }
        word.setLength(0);
    }

    /** 汉字（含扩展 A 与兼容区）。 */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF);
    }
}
