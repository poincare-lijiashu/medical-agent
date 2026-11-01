package com.medassist.kb.parse;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 文档解析：标签清洗、实体解码、Markdown 去语法、二进制与不支持格式的显式拒绝。 */
class DocumentParserTest {

    @Test
    @DisplayName("HTML：脚本/样式整块丢弃，块级标签转换行，标签不进入正文")
    void htmlDropsScriptAndStyle() {
        String html = """
                <html><head><style>.a{color:red}</style><script>var x="阑尾炎";</script></head>
                <body><h1>急性阑尾炎</h1><p>首选手术切除。</p><div>抗生素辅助治疗。</div></body></html>
                """;

        DocumentParser.Parsed parsed = DocumentParser.parse("HTML", null, html);

        assertThat(parsed.kind()).isEqualTo("HTML");
        assertThat(parsed.content()).contains("急性阑尾炎").contains("首选手术切除");
        assertThat(parsed.content()).doesNotContain("color:red").doesNotContain("var x")
                .doesNotContain("<h1>").doesNotContain("<script");
        assertThat(parsed.content()).contains("\n");
        assertThat(parsed.rawChars()).isGreaterThan(parsed.content().length());
    }

    @Test
    @DisplayName("HTML 实体解码：命名实体与十进制/十六进制数字实体都能还原")
    void htmlDecodesEntities() {
        String html = "<p>体温&nbsp;38.5&#8451;，&#x4E2D;性粒细胞&nbsp;&gt;&nbsp;80%&amp;nbsp;</p>";

        String text = DocumentParser.parse("HTML", null, html).content();

        assertThat(text).contains("体温").contains("38.5℃").contains("中性粒细胞")
                .contains(">").contains("80%");
        // &amp;nbsp; 只能被解一次，不能变成空格（否则原文语义丢失）
        assertThat(text).contains("&nbsp;");
    }

    @Test
    @DisplayName("Markdown：标题井号/列表符号/粗体/链接/图片/代码围栏/表格分隔行全部剔除，正文保留")
    void markdownStripsSyntax() {
        String md = """
                # 社区获得性肺炎诊治
                ## 一、诊断
                - **主要标准**：新出现的咳嗽、咳痰
                - 次要标准：[影像学](https://example.com) 见新发浸润影
                ![示意图](img/x.png)
                1. 首选 `阿莫西林` 口服
                | 项目 | 值 |
                |---|---|
                | 白细胞 | >10×10^9/L |
                """;

        DocumentParser.Parsed parsed = DocumentParser.parse("MARKDOWN", null, md);

        assertThat(parsed.kind()).isEqualTo("MARKDOWN");
        assertThat(parsed.content()).contains("社区获得性肺炎诊治").contains("主要标准")
                .contains("新出现的咳嗽").contains("影像学").contains("示意图")
                .contains("阿莫西林").contains("白细胞");
        assertThat(parsed.content()).doesNotContain("##").doesNotContain("**")
                .doesNotContain("](https://").doesNotContain("```").doesNotContain("|---");
    }

    @Test
    @DisplayName("纯文本：只统一换行与压缩空行，不改动正文（引用保真是第一优先级）")
    void plainTextIsNormalizedOnly() {
        String raw = "第一行\r\n第二行\t带制表符\r\n\r\n\r\n第三行";

        DocumentParser.Parsed parsed = DocumentParser.parse("TEXT", null, raw);

        assertThat(parsed.kind()).isEqualTo("PLAIN");
        assertThat(parsed.content()).isEqualTo("第一行\n第二行\t带制表符\n\n第三行");
    }

    @Test
    @DisplayName("未声明类型时自动探测 HTML（粘贴网页不会把标签当正文入库）")
    void autoDetectsHtml() {
        String raw = "<div>血压 140/90 mmHg</div><div>建议复查</div><div>低盐饮食</div>";

        DocumentParser.Parsed parsed = DocumentParser.parse(null, null, raw);

        assertThat(parsed.kind()).isEqualTo("HTML");
        assertThat(parsed.content()).contains("140/90").doesNotContain("<div>");
    }

    @Test
    @DisplayName("未声明类型时自动探测 Markdown（标题 + 围栏两类信号）")
    void autoDetectsMarkdown() {
        String raw = "# 用药说明\n\n```\n阿司匹林 100mg qd\n```\n";

        DocumentParser.Parsed parsed = DocumentParser.parse("MANUAL", null, raw);

        assertThat(parsed.kind()).isEqualTo("MARKDOWN");
        assertThat(parsed.content()).contains("用药说明").contains("阿司匹林 100mg");
    }

    @Test
    @DisplayName("PDF/Word 等二进制格式：显式 422 拒绝，并给出可执行建议（不静默产生乱码）")
    void rejectsUnsupportedBinaryExtensions() {
        assertThatThrownBy(() -> DocumentParser.parse("UPLOAD",
                "https://files.example.com/guide.pdf?token=1", "%PDF-1.7 ..."))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.KB_PARSE_UNSUPPORTED);
                    assertThat(e.getMessage()).contains("pdf").contains("Markdown");
                });
    }

    @Test
    @DisplayName("正文含 NUL/控制字符：按二进制拒绝，不写入索引")
    void rejectsBinaryContent() {
        String binary = "normal\u0000text\u0001with\u0002controls";

        assertThatThrownBy(() -> DocumentParser.parse("TEXT", null, binary))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.KB_PARSE_UNSUPPORTED));
    }

    @Test
    @DisplayName("清洗后为空（只有脚本/样式）：422，不产生空文档")
    void rejectsEmptyAfterCleaning() {
        assertThatThrownBy(() -> DocumentParser.parse("HTML", null,
                "<html><script>var a=1</script><style>.x{}</style></html>"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("解析告警里始终写明实际解析方式，运营可直接核对入库质量")
    void notesRecordEffectiveKind() {
        DocumentParser.Parsed html = DocumentParser.parse("HTML", null, "<p>正文</p>");
        DocumentParser.Parsed plain = DocumentParser.parse("TEXT", null, "正文");

        assertThat(html.notesText()).startsWith("解析方式=HTML").contains("去除脚本");
        assertThat(plain.notesText()).isEqualTo("解析方式=PLAIN");
    }

    @Test
    @DisplayName("扩展名解析：忽略查询串与目录，大小写不敏感")
    void parsesExtension() {
        assertThat(DocumentParser.extensionOf("/a/b/Guide.PDF?x=1")).isEqualTo("pdf");
        assertThat(DocumentParser.extensionOf("C:\\docs\\x.md")).isEqualTo("md");
        assertThat(DocumentParser.extensionOf("no-extension")).isNull();
    }
}
