package com.medassist.fhir.xds;

import com.medassist.fhir.config.FhirProperties;
import com.medassist.fhir.xds.CdaDocumentBuilder.CdaDocument;
import com.medassist.fhir.xds.XdsSources.DischargeSource;
import com.medassist.fhir.xds.XdsSources.DiagnosisRow;
import com.medassist.fhir.xds.XdsSources.LabSource;
import com.medassist.fhir.xds.XdsSources.MedicationRow;
import com.medassist.fhir.xds.XdsSources.PatientRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CDA 生成契约：模板/编码/转义/边界说明都必须稳定。 */
class CdaDocumentBuilderTest {

    private final CdaDocumentBuilder builder = new CdaDocumentBuilder(new FhirProperties());
    private final Instant now = Instant.parse("2026-11-13T01:30:00Z");

    @Test
    @DisplayName("出院小结：含国家级模板/LOINC 编码，患者姓名按 XML 转义")
    void dischargeSummary() {
        PatientRef patient = new PatientRef(UUID.randomUUID(), "P20261113001",
                "张三 & <李四>", "MALE", LocalDate.parse("1980-01-02"));
        DischargeSource source = new DischargeSource(UUID.randomUUID(), "V20261113001",
                LocalDate.parse("2026-11-01"), "doctor01", UUID.randomUUID(), patient,
                UUID.randomUUID(), "A20261101001", "王医生",
                Instant.parse("2026-11-01T02:00:00Z"), Instant.parse("2026-11-10T02:00:00Z"),
                "D01", "呼吸内科", "一病区", "12");
        List<DiagnosisRow> diagnoses = List.of(
                new DiagnosisRow("J18.900", "肺炎", true, "王医生", now),
                new DiagnosisRow("I10.x00", "高血压病", false, "王医生", now));
        List<MedicationRow> meds = List.of(
                new MedicationRow("阿莫西林胶囊", "0.25g", "0.5g", "每日三次", 7, 21, "粒"));

        CdaDocument doc = builder.dischargeSummary(source, diagnoses, meds,
                "urn:uuid:11111111-1111-1111-1111-111111111111",
                "urn:uuid:11111111-1111-1111-1111-111111111111", 1, now);
        String xml = new String(doc.content(), StandardCharsets.UTF_8);

        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xml).contains("<ClinicalDocument xmlns=\"urn:hl7-org:v3\">");
        assertThat(xml).contains("2.16.156.10011.2.1.1.31");
        assertThat(xml).contains("code=\"18842-5\"");
        assertThat(xml).contains("J18.900 肺炎（主诊断）");
        assertThat(xml).contains("阿莫西林胶囊");
        assertThat(xml).contains("P20261113001");
        // 转义：不能出现未转义的 & 或 < 进入姓名
        assertThat(xml).contains("张三 &amp; &lt;李四&gt;");
        assertThat(xml).doesNotContain("张三 & <李四>");
        assertThat(doc.classCode()).isEqualTo(XdsCodes.DISCHARGE_CLASS);
        assertThat(doc.formatCode()).isEqualTo(XdsCodes.CDA_FORMAT);
        assertThat(doc.mimeType()).isEqualTo("text/xml");
    }

    @Test
    @DisplayName("检验报告：含申请/报告编号、所见与结论，未签发说明不伪造签名")
    void labReport() {
        LabSource source = new LabSource(UUID.randomUUID(), "AP20261113001",
                UUID.randomUUID(), "P20261113002", "李四", "FEMALE",
                LocalDate.parse("1990-03-04"), UUID.randomUUID(), "V20261113002",
                "doctor02", "LAB001", "血常规", "静脉血", "发热待查", UUID.randomUUID(),
                UUID.randomUUID(), "RP20261113001", 1, "白细胞 12.3×10^9/L",
                "白细胞升高", "检验师甲", "审核医师乙",
                Instant.parse("2026-11-13T03:00:00Z"), "FINAL",
                Instant.parse("2026-11-13T02:00:00Z"));

        CdaDocument doc = builder.labReport(source,
                "urn:uuid:22222222-2222-2222-2222-222222222222",
                "urn:uuid:22222222-2222-2222-2222-222222222222", 1, now);
        String xml = new String(doc.content(), StandardCharsets.UTF_8);

        assertThat(xml).contains("2.16.156.10011.2.1.1.28");
        assertThat(xml).contains("code=\"11502-2\"");
        assertThat(xml).contains("RP20261113001");
        assertThat(xml).contains("白细胞 12.3×10^9/L");
        assertThat(xml).contains("白细胞升高");
        assertThat(xml).contains("本文档由铭医系统");
        assertThat(doc.formatCode()).isEqualTo(XdsCodes.LAB_FORMAT);
    }

    @Test
    @DisplayName("XML 转义覆盖 5 个特殊字符并剔除控制字符")
    void escape() {
        assertThat(CdaDocumentBuilder.esc("a&b<c>d\"e'f")).isEqualTo("a&amp;b&lt;c&gt;d&quot;e&apos;f");
        assertThat(CdaDocumentBuilder.esc("a\u0000b\u0007c")).isEqualTo("abc");
    }
}
