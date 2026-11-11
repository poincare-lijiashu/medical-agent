package com.medassist.masterdata.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 RFC 4180 CSV 读写。
 *
 * <p>只处理映射导入/导出需要的场景：逗号分隔、双引号包裹、{@code ""} 转义、
 * 引号内允许换行、兼容 CRLF。导出带 UTF-8 BOM，避免 Excel 打开中文乱码。
 *
 * <p>刻意不引入第三方 CSV 库：映射表结构固定（8 列），自实现可以精确控制
 * 错误提示（哪一行第几列不合法），也避免为一个导入工具增加供应链依赖。
 */
public final class Csv {

    private Csv() {
    }

    /** 解析为二维表；首行通常是表头，由调用方决定是否跳过。 */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        String content = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    // CRLF 的 CR 忽略，行尾由 LF 处理
                }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    addRow(rows, row);
                    row = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            addRow(rows, row);
        }
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        boolean blank = row.stream().allMatch(value -> value == null || value.isBlank());
        if (!blank) {
            rows.add(row);
        }
    }

    /** 生成 CSV 文本（含 BOM，便于 Excel 正确识别 UTF-8）。 */
    public static String write(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        appendRow(sb, header);
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        boolean needsQuote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needsQuote) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
