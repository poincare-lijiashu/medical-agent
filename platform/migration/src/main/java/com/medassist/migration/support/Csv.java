package com.medassist.migration.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小但完整的 RFC4180 CSV 读写。
 *
 * <p>医院导出的 CSV 常见三种坑：Windows 记事本加的 UTF-8 BOM、单元格内换行、
 * 双引号转义（""）。用 {@code split(",")} 会在真实数据上直接错列，
 * 因此这里按状态机解析；导出统一加 BOM，Excel 双击打开中文不乱码。
 */
public final class Csv {

    private static final char BOM = '\uFEFF';

    private Csv() {
    }

    /** 解析首个非空行为表头，返回「表头 → 单元格」列表（单元格已去掉首尾空白）。 */
    public static List<Map<String, String>> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<List<String>> rows = parseRows(stripBom(text));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            header.set(i, header.get(i).trim());
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            if (cells.size() == 1 && cells.get(0).isBlank()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), c < cells.size() ? cells.get(c).trim() : "");
            }
            result.add(row);
        }
        return result;
    }

    /** 解析后的首行表头（用于模板/列名校验）。 */
    public static List<String> header(String text) {
        List<List<String>> rows = parseRows(stripBom(text == null ? "" : text));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = new ArrayList<>(rows.get(0));
        header.replaceAll(String::trim);
        return header;
    }

    /** 导出 CSV（带 UTF-8 BOM，CRLF 行尾）。 */
    public static String write(List<String> header, List<Map<String, ?>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(BOM);
        appendRow(sb, header.stream().map(h -> (Object) h).toList());
        for (Map<String, ?> row : rows) {
            appendRow(sb, header.stream().map(h -> row.get(h)).toList());
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    private static List<List<String>> parseRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    // 只有单元格起始处的引号才进入引用态（避免把 a"b 当成非法 CSV 直接吞掉）
                    if (cell.toString().isBlank()) {
                        cell.setLength(0);
                        quoted = true;
                    } else {
                        cell.append(c);
                    }
                }
                case ',' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                }
                case '\n' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                }
                case '\r' -> {
                    // 交给 \n 处理；单独的 \r 也视为换行
                    if (i + 1 >= text.length() || text.charAt(i + 1) != '\n') {
                        row.add(cell.toString());
                        cell.setLength(0);
                        rows.add(row);
                        row = new ArrayList<>();
                    }
                }
                default -> cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
