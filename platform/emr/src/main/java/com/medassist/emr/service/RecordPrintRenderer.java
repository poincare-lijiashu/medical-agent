package com.medassist.emr.service;

import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.RecordSignature;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 病历打印视图（HTML）。
 *
 * <p>离线交付不引入 PDF 生成库：由浏览器"打印 → 另存为 PDF"完成导出，
 * 既避免新增依赖，也能让医院按自己的纸张与页眉模板调整样式。
 * 所有来自数据库的文本都做 HTML 转义，防止病历内容注入脚本。
 */
public final class RecordPrintRenderer {

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private static final Map<String, String> DOC_TYPE_NAMES = Map.of(
            "ADMISSION_NOTE", "入院记录",
            "FIRST_COURSE", "首次病程记录",
            "PROGRESS_NOTE", "病程记录",
            "DISCHARGE_SUMMARY", "出院小结",
            "OPERATION_NOTE", "手术记录",
            "OUTPATIENT_NOTE", "门诊病历",
            "CONSULT_NOTE", "会诊记录");

    private static final Map<String, String> STATUS_NAMES = Map.of(
            "DRAFT", "草稿",
            "SUBMITTED", "已提交",
            "SIGNED", "已签名",
            "ARCHIVED", "已归档");

    private RecordPrintRenderer() {
    }

    public static String render(MedicalRecord record, List<RecordSignature> signatures) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(esc(record.title())).append("</title>")
                .append("<style>")
                .append("@page{size:A4;margin:18mm 16mm}")
                .append("body{font-family:\"Songti SC\",\"SimSun\",\"Noto Serif CJK SC\",serif;")
                .append("color:#111;font-size:12pt;line-height:1.7;margin:0}")
                .append(".sheet{max-width:180mm;margin:0 auto}")
                .append("h1{font-size:18pt;text-align:center;margin:0 0 6mm;letter-spacing:2px}")
                .append(".meta{display:flex;flex-wrap:wrap;gap:3mm 8mm;font-size:10.5pt;")
                .append("border-bottom:1px solid #333;padding-bottom:3mm;margin-bottom:5mm}")
                .append(".meta span{min-width:52mm}")
                .append(".body{white-space:pre-wrap;word-break:break-word;margin:0 0 8mm}")
                .append(".sigs{border-top:1px dashed #888;padding-top:3mm;font-size:10.5pt}")
                .append(".sigs div{margin-bottom:1.5mm}")
                .append(".hash{font-family:Consolas,monospace;font-size:7.5pt;color:#555;")
                .append("word-break:break-all;margin-top:4mm}")
                .append(".foot{text-align:center;font-size:9pt;color:#666;margin-top:6mm}")
                .append(".wm{position:fixed;top:45%;left:50%;transform:translate(-50%,-50%) rotate(-28deg);")
                .append("font-size:58pt;color:rgba(190,0,0,.13);letter-spacing:10px;")
                .append("white-space:nowrap;pointer-events:none;z-index:9}")
                .append("@media print{.no-print{display:none}}")
                .append("</style></head><body>");

        // 未归档文书打印必须带水印：水印随每一页出现（position:fixed），避免草稿/已签名稿被当作正式病历使用
        if (!MedicalRecord.ARCHIVED.equals(record.status())) {
            sb.append("<div class=\"wm\">未归档 · NOT ARCHIVED</div>");
        }
        sb.append("<div class=\"sheet\">");

        sb.append("<h1>").append(esc(docTypeName(record.docType()))).append("</h1>");

        sb.append("<div class=\"meta\">")
                .append("<span>病历号：").append(esc(record.recordNo())).append("</span>")
                .append("<span>患者号：").append(esc(record.patientNo())).append("</span>")
                .append("<span>住院/就诊号：").append(esc(record.visitNo())).append("</span>")
                .append("<span>书写医师：").append(esc(record.author())).append("</span>")
                .append("<span>版本：第 ").append(record.revision()).append(" 版</span>")
                .append("<span>状态：").append(esc(statusName(record.status()))).append("</span>")
                .append("</div>");

        sb.append("<div class=\"body\">").append(esc(record.contentText())).append("</div>");

        if (signatures != null && !signatures.isEmpty()) {
            sb.append("<div class=\"sigs\">");
            for (RecordSignature s : signatures) {
                if (s.revision() != record.revision()) {
                    continue;
                }
                sb.append("<div>").append(esc(RecordSignature.levelName(
                                RecordSignature.levelOf(s.signType()))))
                        .append("签名：").append(esc(s.signer()))
                        .append("　　签署时间：").append(TS.format(s.signedAt()))
                        .append("</div>");
            }
            sb.append("</div>");
        }

        if (record.contentHash() != null) {
            sb.append("<div class=\"hash\">内容摘要 SHA-256：").append(esc(record.contentHash()))
                    .append("</div>");
        }
        sb.append("<div class=\"foot\">本页由 MedAssist 电子病历系统生成，")
                .append("可通过「打印 → 另存为 PDF」归档</div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    static String docTypeName(String docType) {
        return DOC_TYPE_NAMES.getOrDefault(docType == null ? "" : docType, "病历文书");
    }

    static String statusName(String status) {
        return STATUS_NAMES.getOrDefault(status == null ? "" : status, "未知");
    }

    /** HTML 转义：病历正文完全来自用户输入，必须转义后再拼接。 */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
