package com.medassist.fhir.xds;

import com.medassist.fhir.config.FhirProperties;
import com.medassist.fhir.xds.XdsSources.DiagnosisRow;
import com.medassist.fhir.xds.XdsSources.DischargeSource;
import com.medassist.fhir.xds.XdsSources.LabSource;
import com.medassist.fhir.xds.XdsSources.MedicationRow;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CDA R2 文档生成（出院小结 / 检验报告）。
 *
 * <p><b>定位</b>：生成的是"可共享的临床文档"，不是新的病历真源。文档内容来自
 * clinical/tech/empi 的只读投影，生成后原样落库并登记到 XDS 注册表；
 * 后续业务变更不会回写已注册文档（版本化用新的 uniqueId）。
 *
 * <p><b>诚实边界</b>：文档不含 CA 签名，正文明确写明"系统生成、须执业医师复核"。
 * 国家级模板 Schematron 校验、电子签名与时间戳属于外部联调/资质项，
 * 不在本类里伪造。
 */
@Component
public class CdaDocumentBuilder {

    private static final String CDA_NS = "urn:hl7-org:v3";
    private static final String LOINC_OID = "2.16.840.1.113883.6.1";
    private static final String CONFIDENTIALITY_OID = "2.16.840.1.113883.5.25";
    private static final String GENDER_OID = "2.16.840.1.113883.5.1";
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Shanghai"));

    private final FhirProperties properties;

    public CdaDocumentBuilder(FhirProperties properties) {
        this.properties = properties;
    }

    /** 生成结果：内容 + 可直接写入注册表的元数据。 */
    public record CdaDocument(byte[] content, String title, String mimeType,
                              String classCode, String classDisplay,
                              String typeCode, String typeDisplay,
                              String formatCode, String formatDisplay,
                              String confidentialityCode,
                              Instant serviceStart, Instant serviceStop,
                              String authorPerson, String authorInstitution) {
    }

    // ------------------------------------------------------------ 出院小结

    public CdaDocument dischargeSummary(DischargeSource s, List<DiagnosisRow> diagnoses,
                                        List<MedicationRow> medications,
                                        String documentUid, String logicalId, int version,
                                        Instant now) {
        String title = "出院小结（" + safe(s.deptName()) + " "
                + (s.dischargedAt() == null ? DATE.format(now) : DATE.format(s.dischargedAt())) + "）";
        StringBuilder sb = header(title, documentUid, logicalId, version, now,
                XdsCodes.DISCHARGE_CLASS, XdsCodes.DISCHARGE_CLASS_DISPLAY,
                properties.getXdsDischargeTemplateId(), s.patient(),
                s.attendingDoctor() == null ? s.doctorId() : s.attendingDoctor(),
                s.deptName(), s.admittedAt(), s.dischargedAt());

        section(sb, ".21.1", "患者基本信息", List.of(
                line("姓名", s.patient() == null ? null : s.patient().name()),
                line("性别", genderText(s.patient() == null ? null : s.patient().gender())),
                line("出生日期", s.patient() == null || s.patient().birthDate() == null
                        ? null : s.patient().birthDate().toString()),
                line("患者号", s.patient() == null ? null : s.patient().patientNo()),
                line("住院号", s.admissionNo()),
                line("科室", s.deptName()),
                line("病区", s.wardName()),
                line("床号", s.bedNo()),
                line("入院时间", tsText(s.admittedAt())),
                line("出院时间", tsText(s.dischargedAt()))));

        StringBuilder dxText = new StringBuilder();
        if (diagnoses.isEmpty()) {
            dxText.append("本次住院未记录结构化诊断。");
        } else {
            int i = 1;
            for (DiagnosisRow d : diagnoses) {
                if (i > 1) {
                    dxText.append('\n');
                }
                dxText.append(i++).append(". ").append(safe(d.code())).append(' ')
                        .append(safe(d.name()));
                if (d.primaryFlag()) {
                    dxText.append("（主诊断）");
                }
            }
        }
        section(sb, ".21.2", "出院诊断", List.of(dxText.toString()), "29308-4");

        StringBuilder medText = new StringBuilder();
        if (medications.isEmpty()) {
            medText.append("本次住院未记录出院带药。");
        } else {
            int i = 1;
            for (MedicationRow m : medications) {
                if (i > 1) {
                    medText.append('\n');
                }
                medText.append(i++).append(". ").append(safe(m.drugName()));
                if (m.spec() != null) {
                    medText.append(' ').append(safe(m.spec()));
                }
                if (m.dosage() != null) {
                    medText.append(' ').append(safe(m.dosage()));
                }
                if (m.frequency() != null) {
                    medText.append(' ').append(safe(m.frequency()));
                }
                medText.append(' ').append(m.quantity()).append(safe(m.unit()));
                if (m.days() != null) {
                    medText.append(' ').append(m.days()).append("天");
                }
            }
        }
        section(sb, ".21.3", "出院带药", List.of(medText.toString()), "10183-2");
        generationNote(sb);
        footer(sb);

        return new CdaDocument(sb.toString().getBytes(StandardCharsets.UTF_8), title,
                XdsCodes.CDA_MIME, XdsCodes.DISCHARGE_CLASS, XdsCodes.DISCHARGE_CLASS_DISPLAY,
                XdsCodes.DISCHARGE_TYPE, XdsCodes.DISCHARGE_TYPE_DISPLAY,
                XdsCodes.CDA_FORMAT, XdsCodes.CDA_FORMAT_DISPLAY, "N",
                s.admittedAt(), s.dischargedAt(),
                s.attendingDoctor() == null ? s.doctorId() : s.attendingDoctor(), s.deptName());
    }

    // ------------------------------------------------------------ 检验报告

    public CdaDocument labReport(LabSource s, String documentUid, String logicalId, int version,
                                 Instant now) {
        String title = "检验报告（" + safe(s.itemName()) + "）";
        XdsSources.PatientRef patient = new XdsSources.PatientRef(s.patientId(), s.patientNo(),
                s.patientName(), s.gender(), s.birthDate());
        StringBuilder sb = header(title, documentUid, logicalId, version, now,
                XdsCodes.LAB_CLASS, XdsCodes.LAB_CLASS_DISPLAY,
                properties.getXdsLabTemplateId(), patient, s.reporter(), null,
                s.createdAt(), s.reviewedAt());

        section(sb, ".22.1", "患者与申请信息", List.of(
                line("姓名", s.patientName()),
                line("性别", genderText(s.gender())),
                line("患者号", s.patientNo()),
                line("申请单号", s.applicationNo()),
                line("检验项目", s.itemName()),
                line("标本类型", s.specimenType()),
                line("临床诊断", s.clinicalDiag()),
                line("申请医师", s.appliedBy()),
                line("报告单号", s.reportNo() + "（第 " + s.revision() + " 版）"),
                line("报告医师", s.reporter()),
                line("审核者", s.reviewer()),
                line("报告时间", tsText(s.createdAt()))));

        section(sb, ".22.2", "检验结果", List.of(
                line("所见", s.findings()),
                line("结论", s.conclusion())), "30954-2");

        generationNote(sb);
        footer(sb);

        return new CdaDocument(sb.toString().getBytes(StandardCharsets.UTF_8), title,
                XdsCodes.CDA_MIME, XdsCodes.LAB_CLASS, XdsCodes.LAB_CLASS_DISPLAY,
                XdsCodes.LAB_TYPE, XdsCodes.LAB_TYPE_DISPLAY,
                XdsCodes.LAB_FORMAT, XdsCodes.LAB_FORMAT_DISPLAY, "N",
                s.createdAt(), s.reviewedAt(), s.reporter(),
                s.reviewedAt() == null ? null : "检验科");
    }

    // ------------------------------------------------------------ 文档骨架

    private StringBuilder header(String title, String documentUid, String logicalId, int version,
                                 Instant now, String classCode, String classDisplay,
                                 String templateId, XdsSources.PatientRef patient,
                                 String authorPerson, String authorInstitution,
                                 Instant serviceStart, Instant serviceStop) {
        String root = rootOid();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<ClinicalDocument xmlns=\"").append(CDA_NS).append("\">\n");
        sb.append("  <realmCode code=\"CN\"/>\n");
        sb.append("  <typeId root=\"2.16.840.1.113883.1.3\" extension=\"POCD_MT000040\"/>\n");
        sb.append("  <templateId root=\"").append(esc(templateId)).append("\"/>\n");
        sb.append("  <id root=\"").append(root).append(".9\" extension=\"")
                .append(esc(documentUid)).append("\"/>\n");
        sb.append("  <code code=\"").append(esc(classCode)).append("\" codeSystem=\"")
                .append(LOINC_OID).append("\" displayName=\"").append(esc(classDisplay))
                .append("\"/>\n");
        sb.append("  <title>").append(esc(title)).append("</title>\n");
        sb.append("  <effectiveTime value=\"").append(TS.format(now)).append("\"/>\n");
        sb.append("  <confidentialityCode code=\"N\" codeSystem=\"")
                .append(CONFIDENTIALITY_OID).append("\"/>\n");
        sb.append("  <languageCode code=\"").append(XdsCodes.DEFAULT_LANGUAGE).append("\"/>\n");
        sb.append("  <setId root=\"").append(root).append(".8\" extension=\"")
                .append(esc(logicalId)).append("\"/>\n");
        sb.append("  <versionNumber value=\"").append(version).append("\"/>\n");

        sb.append("  <recordTarget>\n    <patientRole>\n");
        sb.append("      <id root=\"").append(root).append(".10\" extension=\"")
                .append(esc(patient == null ? null : patient.patientNo())).append("\"/>\n");
        sb.append("      <patient>\n");
        sb.append("        <name>").append(esc(patient == null ? null : patient.name()))
                .append("</name>\n");
        sb.append("        <administrativeGenderCode code=\"")
                .append(genderCode(patient == null ? null : patient.gender()))
                .append("\" codeSystem=\"").append(GENDER_OID).append("\"/>\n");
        if (patient != null && patient.birthDate() != null) {
            sb.append("        <birthTime value=\"").append(DATE.format(patient.birthDate()))
                    .append("\"/>\n");
        }
        sb.append("      </patient>\n    </patientRole>\n  </recordTarget>\n");

        sb.append("  <author>\n    <time value=\"").append(TS.format(now)).append("\"/>\n");
        sb.append("    <assignedAuthor>\n");
        sb.append("      <id root=\"").append(root).append(".11\" extension=\"")
                .append(esc(authorPerson)).append("\"/>\n");
        sb.append("      <assignedPerson><name>").append(esc(authorPerson))
                .append("</name></assignedPerson>\n");
        sb.append("      <representedOrganization><name>")
                .append(esc(authorInstitution == null ? properties.getXdsCustodianName()
                        : authorInstitution))
                .append("</name></representedOrganization>\n");
        sb.append("    </assignedAuthor>\n  </author>\n");

        sb.append("  <custodian>\n    <assignedCustodian>\n")
                .append("      <representedCustodianOrganization>\n")
                .append("        <id root=\"").append(root).append(".12\"/>\n")
                .append("        <name>").append(esc(properties.getXdsCustodianName()))
                .append("</name>\n")
                .append("      </representedCustodianOrganization>\n")
                .append("    </assignedCustodian>\n  </custodian>\n");

        sb.append("  <documentationOf>\n    <serviceEvent classCode=\"ACT\">\n")
                .append("      <effectiveTime>\n");
        if (serviceStart != null) {
            sb.append("        <low value=\"").append(TS.format(serviceStart)).append("\"/>\n");
        }
        if (serviceStop != null) {
            sb.append("        <high value=\"").append(TS.format(serviceStop)).append("\"/>\n");
        }
        sb.append("      </effectiveTime>\n")
                .append("    </serviceEvent>\n  </documentationOf>\n");
        sb.append("  <component>\n    <structuredBody>\n");
        return sb;
    }

    private void section(StringBuilder sb, String suffix, String title, List<String> paragraphs) {
        section(sb, suffix, title, paragraphs, null);
    }

    private void section(StringBuilder sb, String suffix, String title, List<String> paragraphs,
                         String loincCode) {
        String root = rootOid();
        sb.append("      <component>\n        <section>\n");
        sb.append("          <templateId root=\"").append(root).append(suffix).append("\"/>\n");
        if (loincCode == null) {
            sb.append("          <code code=\"").append(root).append(suffix)
                    .append("\" codeSystem=\"").append(root)
                    .append("\" displayName=\"").append(esc(title)).append("\"/>\n");
        } else {
            sb.append("          <code code=\"").append(esc(loincCode)).append("\" codeSystem=\"")
                    .append(LOINC_OID).append("\" displayName=\"").append(esc(title))
                    .append("\"/>\n");
        }
        sb.append("          <title>").append(esc(title)).append("</title>\n");
        sb.append("          <text>\n");
        for (String paragraph : paragraphs) {
            if (paragraph == null || paragraph.isBlank()) {
                continue;
            }
            for (String line : paragraph.split("\n", -1)) {
                if (!line.isBlank()) {
                    sb.append("            <paragraph>").append(esc(line))
                            .append("</paragraph>\n");
                }
            }
        }
        sb.append("          </text>\n        </section>\n      </component>\n");
    }

    private void generationNote(StringBuilder sb) {
        section(sb, ".23.1", "生成说明", List.of(
                "本文档由铭医系统根据院内记录自动生成，用于区域文档共享与调阅。",
                "文档内容未经电子签名，不替代院内签署归档的病历；临床结论须由执业医师复核。",
                "如发现内容与院内记录不一致，请以院内最终归档版本为准并联系信息科。"));
    }

    private void footer(StringBuilder sb) {
        sb.append("    </structuredBody>\n  </component>\n</ClinicalDocument>\n");
    }

    // ------------------------------------------------------------ 工具

    private String rootOid() {
        String root = properties.getXdsRootOid();
        if (root == null || root.isBlank()) {
            return "1.2.156.112.1.1";
        }
        return root.trim().replaceAll("[^0-9.]", "");
    }

    private static String line(String label, String value) {
        return label + "：" + (value == null || value.isBlank() ? "—" : value.trim());
    }

    private static String tsText(Instant value) {
        return value == null ? null : TS.format(value);
    }

    private static String genderCode(String gender) {
        if (gender == null) {
            return "UN";
        }
        return switch (gender.trim().toUpperCase()) {
            case "MALE", "M", "男" -> "M";
            case "FEMALE", "F", "女" -> "F";
            default -> "UN";
        };
    }

    private static String genderText(String gender) {
        return switch (genderCode(gender)) {
            case "M" -> "男";
            case "F" -> "女";
            default -> "未知";
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    /** XML 1.0 转义 + 去掉不可见控制字符（否则整份文档在客户端解析失败）。 */
    static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
