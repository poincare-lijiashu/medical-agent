package com.medassist.insurance.service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 医保目录文件解析（CSV / XML）。
 *
 * <p>支持的输入：
 * <ul>
 *   <li>CSV：UTF-8（允许 BOM），首行表头；支持英文列名与中文列名（见 {@link #ALIASES}），
 *       字段可用双引号包裹以包含逗号/换行；</li>
 *   <li>XML：{@code <catalog><item code="..." name="..." category="DRUG" payCategory="A"
 *       selfPayRatio="0.1" priceLimit="50.00" enabled="true"/></catalog>}，
 *       也接受子元素形式（{@code <code>..</code>}）；</li>
 *   <li>Excel（.xlsx/.xls）明确拒绝并提示另存为 CSV——不引入 POI，保持离网交付轻量。</li>
 * </ul>
 *
 * <p>解析阶段只做**结构与取值校验**（必填、枚举、比例区间、行内重复编码），不做任何写库；
 * 有任一错误即整体拒绝（不静默跳过坏行，避免"文件导了一半"）。
 */
public final class CatalogFileParser {

    /** 单文件最大行数：目录文件按月下发，5000 行足够覆盖常见地市；超出请拆分。 */
    public static final int MAX_ROWS = 5000;

    /** 解析时扫描的物理记录上限（含空行）：防止纯逗号文件把内存放大到数百倍（第四轮 SF-11）。 */
    public static final int MAX_RECORDS = MAX_ROWS * 4;

    /** 单个单元格最大长度：超出即视为坏文件，避免超长字段进日志/响应（第四轮 SF-11）。 */
    public static final int MAX_CELL_LENGTH = 2048;

    /** 返回给调用方的错误条数上限（避免一个坏文件刷屏）。 */
    public static final int MAX_ERRORS = 20;

    /** 错误消息里回显单元格原文的最大长度（第四轮 SF-11）。 */
    private static final int MAX_ECHO_LENGTH = 50;

    private static final Set<String> CATEGORIES = Set.of("DRUG", "SERVICE", "MATERIAL", "DIAGNOSIS");
    private static final Set<String> PAY_CATEGORIES = Set.of("A", "B", "C");

    /** 表头/属性/子元素的别名归一化映射（归一化规则：小写、去空格/下划线/连字符）。 */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        alias("code", "code", "insurancecode", "itemcode", "医保编码", "医保项目编码", "项目编码");
        alias("name", "name", "itemname", "名称", "项目名称", "医保项目名称");
        alias("category", "category", "type", "类别", "目录类别", "项目类别");
        alias("paycategory", "paycategory", "paytype", "支付类别", "支付类型", "报销类别");
        alias("selfpayratio", "selfpayratio", "selfratio", "自付比例", "乙类自付比例");
        alias("pricelimit", "pricelimit", "limitprice", "限价", "最高限价");
        alias("enabled", "enabled", "status", "状态", "是否启用", "启用");
    }

    private static void alias(String field, String... names) {
        for (String name : names) {
            ALIASES.put(normalize(name), field);
        }
    }

    private CatalogFileParser() {
    }

    /**
     * 解析结果：rows 与 errors 互斥（有错误时调用方必须整体拒绝）。
     *
     * @param warnings 非阻断提示（如未识别的表头列），随导入结果一并回显给操作员
     */
    public record Parsed(List<Row> rows, List<String> errors, List<String> warnings) {
    }

    /** 一行目录数据；{@code enabled} 为空表示文件未给出状态（更新时保留库里原值）。 */
    public record Row(int lineNo, String code, String name, String category, String payCategory,
                      BigDecimal selfPayRatio, BigDecimal priceLimit, Boolean enabled) {
    }

    public static Parsed parse(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            return fail("文件为空，请上传 CSV 或 XML 目录文件");
        }
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return fail("暂不支持 Excel 文件，请在 Excel 中另存为 CSV（UTF-8）后重试");
        }
        boolean xml = name.endsWith(".xml") || (!name.endsWith(".csv") && startsWithXml(content));
        return xml ? parseXml(content) : parseCsv(content);
    }

    /** 文件摘要（审计留痕用）：防止"审计说导入了某文件，但无法证明是哪一版"。 */
    public static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ---------------------------------------------------------------- CSV

    private static Parsed parseCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        Split split = splitCsv(text);
        if (split.error() != null) {
            return fail(split.error());
        }
        List<CsvRecord> records = split.records();
        int cursor = 0;
        CsvRecord header = null;
        while (cursor < records.size()) {
            if (!records.get(cursor).blank()) {
                header = records.get(cursor);
                cursor++;
                break;
            }
        }
        if (header == null) {
            return fail("CSV 缺少表头");
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> headerErrors = new ArrayList<>();
        for (int i = 0; i < header.cells().size(); i++) {
            String raw = header.cells().get(i);
            String field = ALIASES.get(normalize(raw));
            if (field == null) {
                if (raw != null && !raw.isBlank()) {
                    warnings.add("未识别的列已忽略：" + echo(raw));
                }
                continue;
            }
            // 同字段出现多列（如"医保编码"与"项目编码"同时存在）：静默取首列会让另一列的值被无声丢弃
            Integer previous = columns.putIfAbsent(field, i);
            if (previous != null) {
                headerErrors.add("表头存在重复含义的列：" + echo(raw)
                        + "（与第 " + (previous + 1) + " 列指向同一字段 " + field + "）");
            }
        }
        if (!headerErrors.isEmpty()) {
            return new Parsed(List.of(), trimErrors(headerErrors), List.of());
        }
        List<String> missing = new ArrayList<>();
        for (String required : List.of("code", "name", "category", "paycategory")) {
            if (!columns.containsKey(required)) {
                missing.add(requiredLabel(required));
            }
        }
        if (!missing.isEmpty()) {
            return fail("CSV 缺少必需列：" + String.join("、", missing));
        }

        List<Row> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = cursor; i < records.size(); i++) {
            CsvRecord record = records.get(i);
            if (record.blank()) {
                continue;
            }
            if (record.cells().size() > header.cells().size()) {
                errors.add("第 " + record.lineNo() + " 行：列数多于表头，请检查是否有未加引号的逗号");
                continue;
            }
            Row row = buildRow(record.lineNo(),
                    cell(record, columns, "code"), cell(record, columns, "name"),
                    cell(record, columns, "category"), cell(record, columns, "paycategory"),
                    cell(record, columns, "selfpayratio"), cell(record, columns, "pricelimit"),
                    cell(record, columns, "enabled"), errors);
            if (row != null) {
                rows.add(row);
            }
            if (rows.size() > MAX_ROWS) {
                return fail("目录行数超过上限 " + MAX_ROWS + "，请拆分文件后分批导入");
            }
        }
        return finish(rows, errors, warnings);
    }

    private static String cell(CsvRecord record, Map<String, Integer> columns, String field) {
        Integer index = columns.get(field);
        if (index == null || index >= record.cells().size()) {
            return null;
        }
        return record.cells().get(index);
    }

    /** 切分结果：error 非空表示文件结构不可用（引号未闭合、超长字段、记录数超限）。 */
    private record Split(List<CsvRecord> records, String error) {
    }

    /** RFC4180 风格切分：引号内允许逗号与换行，{@code ""} 表示一个引号。 */
    private static Split splitCsv(String text) {
        List<CsvRecord> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        int quotedFromLine = 0;
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
                    if (c == '\n') {
                        line++;
                    }
                    if (cell.length() >= MAX_CELL_LENGTH) {
                        return new Split(List.of(), "第 " + quotedFromLine
                                + " 行存在超过 " + MAX_CELL_LENGTH + " 字符的超长字段，请检查文件");
                    }
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                quotedFromLine = line;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r') {
                // CRLF：忽略 CR，换行由 LF 处理；单独的 CR 也视为换行
                if (i + 1 >= text.length() || text.charAt(i + 1) != '\n') {
                    line++;
                    cells.add(cell.toString());
                    cell.setLength(0);
                    if (records.size() >= MAX_RECORDS) {
                        return tooManyRecords();
                    }
                    records.add(new CsvRecord(recordLine, List.copyOf(cells)));
                    cells.clear();
                    recordLine = line;
                }
            } else if (c == '\n') {
                line++;
                cells.add(cell.toString());
                cell.setLength(0);
                if (records.size() >= MAX_RECORDS) {
                    return tooManyRecords();
                }
                records.add(new CsvRecord(recordLine, List.copyOf(cells)));
                cells.clear();
                recordLine = line;
            } else {
                if (cell.length() >= MAX_CELL_LENGTH) {
                    return new Split(List.of(), "第 " + line
                            + " 行存在超过 " + MAX_CELL_LENGTH + " 字符的超长字段，请检查文件");
                }
                cell.append(c);
            }
        }
        // 引号未闭合：后续所有内容会被并入同一字段，静默入库会把多行脏数据写进名称之类的字段
        if (quoted) {
            return new Split(List.of(), "第 " + quotedFromLine
                    + " 行的引号未闭合，请检查 CSV 格式后重新上传");
        }
        if (cell.length() > 0 || !cells.isEmpty()) {
            cells.add(cell.toString());
            records.add(new CsvRecord(recordLine, List.copyOf(cells)));
        }
        return new Split(records, null);
    }

    private static Split tooManyRecords() {
        return new Split(List.of(), "CSV 记录数超过上限 " + MAX_RECORDS + "，请拆分文件后分批导入");
    }

    private record CsvRecord(int lineNo, List<String> cells) {
        boolean blank() {
            return cells.stream().allMatch(c -> c == null || c.isBlank());
        }
    }

    // ---------------------------------------------------------------- XML

    private static Parsed parseXml(byte[] content) {
        List<Row> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // 目录文件来自外部：必须关闭 DTD 与外部实体（XXE）
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT
                        || !"item".equals(localName(reader))) {
                    continue;
                }
                int line = reader.getLocation().getLineNumber();
                Map<String, String> fields = new HashMap<>();
                for (int i = 0; i < reader.getAttributeCount(); i++) {
                    String field = ALIASES.get(normalize(reader.getAttributeLocalName(i)));
                    if (field != null) {
                        fields.put(field, reader.getAttributeValue(i));
                    }
                }
                String childField = null;
                StringBuilder text = new StringBuilder();
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        childField = ALIASES.get(normalize(localName(reader)));
                        text.setLength(0);
                    } else if (event == XMLStreamConstants.CHARACTERS && childField != null) {
                        text.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT && childField != null) {
                        fields.putIfAbsent(childField, text.toString());
                        childField = null;
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "item".equals(localName(reader))) {
                        break;
                    }
                }
                Row row = buildRow(line, fields.get("code"), fields.get("name"),
                        fields.get("category"), fields.get("paycategory"),
                        fields.get("selfpayratio"), fields.get("pricelimit"),
                        fields.get("enabled"), errors);
                if (row != null) {
                    rows.add(row);
                }
                if (rows.size() > MAX_ROWS) {
                    return fail("目录行数超过上限 " + MAX_ROWS + "，请拆分文件后分批导入");
                }
            }
            reader.close();
        } catch (Exception e) {
            return fail("XML 解析失败：" + e.getMessage());
        }
        if (rows.isEmpty() && errors.isEmpty()) {
            return fail("XML 中未找到 item 节点");
        }
        return finish(rows, errors, List.of());
    }

    private static String localName(XMLStreamReader reader) {
        String name = reader.getLocalName();
        return name == null ? "" : name;
    }

    // ---------------------------------------------------------------- 校验

    private static Row buildRow(int lineNo, String codeRaw, String nameRaw, String categoryRaw,
                                String payCategoryRaw, String ratioRaw, String limitRaw,
                                String enabledRaw, List<String> errors) {
        int before = errors.size();
        String code = trimToNull(stripControlChars(codeRaw));
        if (code == null) {
            errors.add("第 " + lineNo + " 行：医保编码为空");
        } else if (code.length() > 64) {
            errors.add("第 " + lineNo + " 行：医保编码超过 64 个字符");
        }
        String name = trimToNull(stripControlChars(nameRaw));
        if (name == null) {
            errors.add("第 " + lineNo + " 行：项目名称为空");
        } else if (name.length() > 128) {
            errors.add("第 " + lineNo + " 行：项目名称超过 128 个字符");
        }
        String category = upper(trimToNull(stripControlChars(categoryRaw)));
        if (category == null || !CATEGORIES.contains(category)) {
            errors.add("第 " + lineNo + " 行：目录类别必须是 DRUG/SERVICE/MATERIAL/DIAGNOSIS");
        }
        String payCategory = upper(trimToNull(stripControlChars(payCategoryRaw)));
        if (payCategory == null || !PAY_CATEGORIES.contains(payCategory)) {
            errors.add("第 " + lineNo + " 行：支付类别必须是 A/B/C");
        }
        BigDecimal ratio = decimal(lineNo, "自付比例", ratioRaw, errors);
        if (ratio != null && (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0)) {
            errors.add("第 " + lineNo + " 行：自付比例必须在 0~1 之间（或写成 10%）");
        }
        BigDecimal limit = decimal(lineNo, "限价", limitRaw, errors);
        if (limit != null && limit.signum() < 0) {
            errors.add("第 " + lineNo + " 行：限价不能为负数");
        }
        Boolean enabled = enabled(lineNo, enabledRaw, errors);
        if (errors.size() > before) {
            return null;
        }
        return new Row(lineNo, code, name, category, payCategory,
                ratio == null ? null : ratio.setScale(4, RoundingMode.HALF_UP),
                limit == null ? null : limit.setScale(2, RoundingMode.HALF_UP), enabled);
    }

    /** 支持 0.1 与 10% 两种写法（省级目录两种都出现过）。 */
    private static BigDecimal decimal(int lineNo, String label, String raw, List<String> errors) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            if (value.endsWith("%")) {
                BigDecimal percent = new BigDecimal(value.substring(0, value.length() - 1).trim());
                return percent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            }
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            errors.add("第 " + lineNo + " 行：" + label + "不是合法数字：" + echo(value));
            return null;
        }
    }

    private static Boolean enabled(int lineNo, String raw, List<String> errors) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1", "true", "y", "yes", "是", "启用" -> Boolean.TRUE;
            case "0", "false", "n", "no", "否", "停用", "禁用" -> Boolean.FALSE;
            default -> {
                errors.add("第 " + lineNo + " 行：状态只能是 启用/停用（或 true/false、1/0）");
                yield null;
            }
        };
    }

    private static Parsed finish(List<Row> rows, List<String> errors, List<String> warnings) {
        if (!errors.isEmpty()) {
            return new Parsed(List.of(), trimErrors(errors), warnings);
        }
        if (rows.isEmpty()) {
            return fail("文件中没有目录数据");
        }
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Row row : rows) {
            if (!seen.add(row.code())) {
                duplicates.add("第 " + row.lineNo() + " 行：医保编码重复：" + row.code());
            }
        }
        if (!duplicates.isEmpty()) {
            return new Parsed(List.of(), trimErrors(duplicates), warnings);
        }
        return new Parsed(List.copyOf(rows), List.of(), warnings);
    }

    private static List<String> trimErrors(List<String> errors) {
        if (errors.size() <= MAX_ERRORS) {
            return List.copyOf(errors);
        }
        List<String> trimmed = new ArrayList<>(errors.subList(0, MAX_ERRORS));
        trimmed.add("……等共 " + errors.size() + " 处问题，请修正后重新上传");
        return List.copyOf(trimmed);
    }

    private static Parsed fail(String message) {
        return new Parsed(List.of(), List.of(message), List.of());
    }

    /**
     * 去掉控制字符（保留制表符）：控制字符会让 PG 拒写、让报盘 XML 出现非法字符
     * 并被固化进已生成的 payload（第四轮 SF-10）。
     */
    private static String stripControlChars(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || (c >= 0x20 && c != 0x7F)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 错误消息回显：截断到固定长度，避免超长单元格原文进响应与日志（SF-11）。 */
    private static String echo(String s) {
        String value = s == null ? "" : s;
        return value.length() <= MAX_ECHO_LENGTH ? value : value.substring(0, MAX_ECHO_LENGTH) + "…";
    }

    private static String requiredLabel(String field) {
        return switch (field) {
            case "code" -> "医保编码";
            case "name" -> "项目名称";
            case "category" -> "目录类别";
            default -> "支付类别";
        };
    }

    private static boolean startsWithXml(byte[] content) {
        for (byte b : content) {
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                continue;
            }
            return b == '<';
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "").replace("_", "").replace("-", "");
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
