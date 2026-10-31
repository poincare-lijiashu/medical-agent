package com.medassist.insurance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录文件解析（CSV / XML）：中文表头、引号、百分比、重复编码与危险格式。
 */
class CatalogFileParserTest {

    private static CatalogFileParser.Parsed parseCsv(String text) {
        return CatalogFileParser.parse("catalog.csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CSV：BOM + 中文表头 + 引号内逗号 + 百分比自付比例")
    void csvParsesChineseHeadersAndQuotedFields() {
        String csv = "\uFEFF医保编码,项目名称,目录类别,支付类别,自付比例,限价,状态\n"
                + "I-1,\"阿莫西林,胶囊\",DRUG,B,10%,50.00,启用\n"
                + "I-2,门诊诊查费,SERVICE,A,,,\n";

        CatalogFileParser.Parsed parsed = parseCsv(csv);

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(2);
        CatalogFileParser.Row first = parsed.rows().get(0);
        assertThat(first.code()).isEqualTo("I-1");
        assertThat(first.name()).isEqualTo("阿莫西林,胶囊");
        assertThat(first.category()).isEqualTo("DRUG");
        assertThat(first.payCategory()).isEqualTo("B");
        assertThat(first.selfPayRatio()).isEqualByComparingTo(new BigDecimal("0.1000"));
        assertThat(first.priceLimit()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(first.enabled()).isTrue();

        CatalogFileParser.Row second = parsed.rows().get(1);
        assertThat(second.selfPayRatio()).isNull();
        assertThat(second.priceLimit()).isNull();
        assertThat(second.enabled()).isNull();
    }

    @Test
    @DisplayName("CSV：双引号内的 \"\" 解析为一个引号")
    void csvHandlesEscapedQuotes() {
        CatalogFileParser.Parsed parsed = parseCsv(
                "code,name,category,payCategory\nX1,\"a\"\"b\",DRUG,A\n");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).name()).isEqualTo("a\"b");
    }

    @Test
    @DisplayName("XML：属性与子元素两种写法都支持")
    void xmlSupportsAttributesAndChildElements() {
        String xml = """
                <catalog>
                  <item code="X1" name="CT" category="SERVICE" payCategory="B"
                        selfPayRatio="0.2" priceLimit="200"/>
                  <item>
                    <code>X2</code>
                    <name>MRI</name>
                    <category>DIAGNOSIS</category>
                    <payCategory>C</payCategory>
                  </item>
                </catalog>
                """;

        CatalogFileParser.Parsed parsed = CatalogFileParser.parse("catalog.xml",
                xml.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).selfPayRatio()).isEqualByComparingTo("0.2");
        assertThat(parsed.rows().get(0).priceLimit()).isEqualByComparingTo("200.00");
        assertThat(parsed.rows().get(1).code()).isEqualTo("X2");
        assertThat(parsed.rows().get(1).enabled()).isNull();
    }

    @Test
    @DisplayName("Excel：明确拒绝并提示另存为 CSV")
    void xlsxRejected() {
        CatalogFileParser.Parsed parsed = CatalogFileParser.parse("catalog.xlsx", new byte[]{1, 2});

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors()).anyMatch(e -> e.contains("Excel"));
    }

    @Test
    @DisplayName("重复医保编码：整体拒绝")
    void duplicateCodeRejected() {
        CatalogFileParser.Parsed parsed = parseCsv("""
                code,name,category,payCategory
                X1,A,DRUG,A
                X1,B,DRUG,A
                """);

        assertThat(parsed.errors()).anyMatch(e -> e.contains("医保编码重复"));
    }

    @Test
    @DisplayName("非法支付类别：指出行号")
    void invalidPayCategoryRejected() {
        CatalogFileParser.Parsed parsed = parseCsv("""
                code,name,category,payCategory
                X1,A,DRUG,Z
                """);

        assertThat(parsed.errors()).anyMatch(e -> e.contains("第 2 行") && e.contains("A/B/C"));
    }

    @Test
    @DisplayName("缺少必需列：列出列名")
    void missingRequiredColumnsRejected() {
        CatalogFileParser.Parsed parsed = parseCsv("医保编码,项目名称\nX1,A\n");

        assertThat(parsed.errors()).anyMatch(e -> e.contains("缺少必需列"));
    }

    @Test
    @DisplayName("自付比例非法：提示应为 0~1 或百分比")
    void invalidRatioRejected() {
        CatalogFileParser.Parsed parsed = parseCsv("""
                code,name,category,payCategory,selfPayRatio
                X1,A,DRUG,B,abc
                """);

        assertThat(parsed.errors()).anyMatch(e -> e.contains("自付比例"));
    }

    @Test
    @DisplayName("空文件与只有表头：都拒绝")
    void emptyInputsRejected() {
        assertThat(CatalogFileParser.parse("catalog.csv", new byte[0]).errors())
                .anyMatch(e -> e.contains("文件为空"));
        assertThat(parseCsv("code,name,category,payCategory\n").errors())
                .anyMatch(e -> e.contains("没有目录数据"));
    }

    @Test
    @DisplayName("CSV：引号未闭合整体拒绝，不把后续行并入字段（SF-08）")
    void unclosedQuoteRejected() {
        CatalogFileParser.Parsed parsed = parseCsv("code,name,category,payCategory\nX1,\"断引号,DRUG,A\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors()).anyMatch(e -> e.contains("引号未闭合"));
    }

    @Test
    @DisplayName("CSV：同字段多别名并存（医保编码 + 项目编码）报错（SF-09）")
    void duplicateHeaderAliasRejected() {
        CatalogFileParser.Parsed parsed = parseCsv(
                "医保编码,项目编码,项目名称,目录类别,支付类别\nX1,X2,A,DRUG,A\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors()).anyMatch(e -> e.contains("重复含义的列"));
    }

    @Test
    @DisplayName("CSV：未识别表头给出警告但不阻断导入（SF-09）")
    void unknownHeaderWarns() {
        CatalogFileParser.Parsed parsed = parseCsv(
                "code,name,category,payCategory,备注\nX1,A,DRUG,A,ignored\n");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.warnings()).anyMatch(w -> w.contains("未识别的列"));
    }

    @Test
    @DisplayName("CSV：控制字符被剥离（避免 PG 拒写与报盘 XML 非法字符，SF-10）")
    void controlCharsStripped() {
        CatalogFileParser.Parsed parsed = parseCsv(
                "code,name,category,payCategory\nX\u00001,\u0001A\u000BB,DRUG,A\n");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).code()).isEqualTo("X1");
        assertThat(parsed.rows().get(0).name()).isEqualTo("AB");
    }

    @Test
    @DisplayName("CSV：超长单元格整体拒绝（SF-11）")
    void oversizedCellRejected() {
        String longName = "x".repeat(CatalogFileParser.MAX_CELL_LENGTH + 1);

        CatalogFileParser.Parsed parsed = parseCsv(
                "code,name,category,payCategory\nX1," + longName + ",DRUG,A\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors()).anyMatch(e -> e.contains("超长字段"));
    }

    @Test
    @DisplayName("CSV：CRLF 与单独 CR 都按行尾处理（SF-20）")
    void crlfAndLoneCrLineEndings() {
        CatalogFileParser.Parsed crlf = CatalogFileParser.parse("catalog.csv",
                "code,name,category,payCategory\r\nX1,A,DRUG,A\r\n".getBytes(StandardCharsets.UTF_8));
        CatalogFileParser.Parsed cr = CatalogFileParser.parse("catalog.csv",
                "code,name,category,payCategory\rX1,A,DRUG,A\r".getBytes(StandardCharsets.UTF_8));

        assertThat(crlf.errors()).isEmpty();
        assertThat(crlf.rows()).hasSize(1);
        assertThat(cr.errors()).isEmpty();
        assertThat(cr.rows()).hasSize(1);
        assertThat(cr.rows().get(0).code()).isEqualTo("X1");
    }

    @Test
    @DisplayName("SHA-256：64 位十六进制且稳定")
    void sha256Stable() {
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);

        String digest = CatalogFileParser.sha256(content);

        assertThat(digest).hasSize(64).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
