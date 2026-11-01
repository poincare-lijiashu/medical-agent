package com.medassist.emr.service;

import com.medassist.emr.domain.EmrModel.Homepage;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 病案首页打印视图（HTML）。
 *
 * <p>与病历打印一致：不引入 PDF 库，由浏览器"打印 → 另存为 PDF"导出；
 * 所有库内文本都做 HTML 转义；未归档带水印，避免草稿被当成正式首页使用。
 */
public final class HomepagePrintRenderer {

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private static final Map<String, String> STATUS_NAMES = Map.of(
            "DRAFT", "草稿",
            "SUBMITTED", "已提交",
            "ARCHIVED", "已归档");

    private static final Map<String, String> DISCHARGE_MODES = Map.of(
            "BY_DOCTOR", "医嘱离院",
            "TRANSFER", "医嘱转院",
            "TRANSFER_HOME", "医嘱转社区/家庭病床",
            "NON_DOCTOR", "非医嘱离院",
            "DEATH", "死亡",
            "OTHER", "其他");

    private static final Map<String, String> ADMISSION_WAYS = Map.of(
            "OUTPATIENT", "门诊",
            "EMERGENCY", "急诊",
            "TRANSFER", "转入");

    private static final Map<String, String> FEE_NAMES = Map.of(
            "REGISTRATION", "挂号费",
            "CONSULTATION", "诊察费",
            "DRUG", "药品费",
            "TREATMENT", "治疗费",
            "EXAM", "检查检验费",
            "MATERIAL", "材料费");

    private static final Map<String, String> READMIT_PLANS = Map.of(
            "NONE", "无",
            "WITHIN_31_DAYS", "31 日内再住院");

    private HomepagePrintRenderer() {
    }

    public static String render(Homepage h, List<Map<String, Object>> diagnoses,
                                List<Map<String, Object>> procedures,
                                Map<String, BigDecimal> feeSummary) {
        StringBuilder sb = new StringBuilder(3072);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>病案首页 ").append(esc(h.homepageNo())).append("</title>")
                .append("<style>")
                .append("@page{size:A4;margin:16mm 14mm}")
                .append("body{font-family:\"Songti SC\",\"SimSun\",\"Noto Serif CJK SC\",serif;")
                .append("color:#111;font-size:11pt;line-height:1.6;margin:0}")
                .append(".sheet{max-width:182mm;margin:0 auto}")
                .append("h1{font-size:18pt;text-align:center;margin:0 0 5mm;letter-spacing:3px}")
                .append("h2{font-size:12pt;margin:6mm 0 2mm;border-left:3px solid #333;padding-left:2mm}")
                .append(".meta{display:flex;flex-wrap:wrap;gap:2mm 8mm;font-size:10.5pt;")
                .append("border-bottom:1px solid #333;padding-bottom:2mm;margin-bottom:3mm}")
                .append(".meta span{min-width:50mm}")
                .append("table{width:100%;border-collapse:collapse;font-size:10.5pt}")
                .append("th,td{border:1px solid #666;padding:1.2mm 2mm;text-align:left}")
                .append("th{background:#f2f2f2;font-weight:normal}")
                .append(".right{text-align:right}")
                .append(".foot{text-align:center;font-size:9pt;color:#666;margin-top:6mm}")
                .append(".wm{position:fixed;top:45%;left:50%;transform:translate(-50%,-50%) rotate(-28deg);")
                .append("font-size:58pt;color:rgba(190,0,0,.13);letter-spacing:10px;")
                .append("white-space:nowrap;pointer-events:none;z-index:9}")
                .append("</style></head><body>");

        if (!Homepage.ARCHIVED.equals(h.status())) {
            sb.append("<div class=\"wm\">未归档 · NOT ARCHIVED</div>");
        }
        sb.append("<div class=\"sheet\">");
        sb.append("<h1>病案首页</h1>");

        sb.append("<div class=\"meta\">")
                .append("<span>首页号：").append(esc(h.homepageNo())).append("</span>")
                .append("<span>住院号：").append(esc(h.visitNo())).append("</span>")
                .append("<span>患者号：").append(esc(h.patientNo())).append("</span>")
                .append("<span>姓名：").append(esc(h.patientName())).append("</span>")
                .append("<span>性别：").append(esc(sexName(h.patientSex()))).append("</span>")
                .append("<span>年龄：").append(h.admitAge() == null ? "" : h.admitAge())
                .append(" 岁</span>")
                .append("<span>科室：").append(esc(h.deptName())).append("</span>")
                .append("<span>病区/床位：").append(esc(h.wardName())).append(" / ")
                .append(esc(h.bedNo())).append("</span>")
                .append("<span>状态：").append(esc(statusName(h.status()))).append("</span>")
                .append("</div>");

        sb.append("<h2>住院过程</h2><table>");
        sb.append("<tr><th>入院时间</th><td>").append(ts(h.admittedAt()))
                .append("</td><th>出院时间</th><td>").append(ts(h.dischargedAt())).append("</td></tr>");
        sb.append("<tr><th>住院天数</th><td>").append(h.stayDays())
                .append(" 天</td><th>入院途径</th><td>").append(esc(admissionWay(h.admissionWay())))
                .append("</td></tr>");
        sb.append("<tr><th>离院方式</th><td>").append(esc(dischargeMode(h.dischargeMode())))
                .append("</td><th>31 日内再住院计划</th><td>")
                .append(esc(READMIT_PLANS.getOrDefault(nullToEmpty(h.readmitPlan()), h.readmitPlan())))
                .append("</td></tr>");
        sb.append("<tr><th>主治医师</th><td>").append(esc(h.attendingDoctor()))
                .append("</td><th>住院医师</th><td>").append(esc(h.residentPhysician())).append("</td></tr>");
        if ("DEATH".equals(h.dischargeMode())) {
            sb.append("<tr><th>死亡时间</th><td>").append(ts(h.deathTime()))
                    .append("</td><th>死亡原因</th><td>").append(esc(h.deathCause())).append("</td></tr>");
        }
        sb.append("<tr><th>主任医师</th><td>").append(esc(h.chiefPhysician()))
                .append("</td><th>编码员</th><td>").append(esc(h.coder())).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h2>出院诊断</h2><table>")
                .append("<tr><th style=\"width:32mm\">ICD-10</th><th>诊断名称</th>")
                .append("<th style=\"width:22mm\">主诊断</th></tr>");
        if (diagnoses.isEmpty()) {
            sb.append("<tr><td colspan=\"3\">无</td></tr>");
        }
        for (Map<String, Object> d : diagnoses) {
            sb.append("<tr><td>").append(esc(str(d.get("code")))).append("</td><td>")
                    .append(esc(str(d.get("name")))).append("</td><td>")
                    .append(Boolean.TRUE.equals(d.get("primary")) ? "是" : "").append("</td></tr>");
        }
        sb.append("</table>");

        sb.append("<h2>手术操作</h2><table>")
                .append("<tr><th style=\"width:26mm\">编码</th><th>名称</th>")
                .append("<th style=\"width:26mm\">日期</th><th style=\"width:26mm\">术者</th>")
                .append("<th style=\"width:26mm\">麻醉方式</th><th style=\"width:18mm\">切口</th></tr>");
        if (procedures.isEmpty()) {
            sb.append("<tr><td colspan=\"6\">无</td></tr>");
        }
        for (Map<String, Object> p : procedures) {
            sb.append("<tr><td>").append(esc(str(p.get("code")))).append("</td><td>")
                    .append(esc(str(p.get("name")))).append("</td><td>")
                    .append(esc(str(p.get("date")))).append("</td><td>")
                    .append(esc(str(p.get("surgeon")))).append("</td><td>")
                    .append(esc(str(p.get("anesthesia")))).append("</td><td>")
                    .append(esc(str(p.get("incisionLevel")))).append("</td></tr>");
        }
        sb.append("</table>");

        sb.append("<h2>费用汇总</h2><table>")
                .append("<tr><th>类别</th><th class=\"right\" style=\"width:40mm\">金额（元）</th></tr>");
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : feeSummary.entrySet()) {
            total = total.add(e.getValue());
            sb.append("<tr><td>").append(esc(FEE_NAMES.getOrDefault(e.getKey(), e.getKey())))
                    .append("</td><td class=\"right\">").append(money(e.getValue()))
                    .append("</td></tr>");
        }
        sb.append("<tr><th>合计</th><th class=\"right\">").append(money(total)).append("</th></tr>");
        sb.append("</table>");

        sb.append("<div class=\"foot\">本页由 MedAssist 电子病历系统生成，")
                .append("可通过「打印 → 另存为 PDF」归档</div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String ts(java.time.Instant instant) {
        return instant == null ? "" : TS.format(instant);
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "0.00"
                : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static String statusName(String status) {
        return STATUS_NAMES.getOrDefault(nullToEmpty(status), "未知");
    }

    static String dischargeMode(String mode) {
        return mode == null ? "" : DISCHARGE_MODES.getOrDefault(mode, mode);
    }

    /** 性别展示（首页打印/HQMS 口径统一为 MALE/FEMALE/UNKNOWN）。 */
    static String sexName(String sex) {
        return switch (nullToEmpty(sex)) {
            case "MALE" -> "男";
            case "FEMALE" -> "女";
            default -> "未知";
        };
    }

    static String admissionWay(String way) {
        return way == null ? "" : ADMISSION_WAYS.getOrDefault(way, way);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String esc(String s) {
        return RecordPrintRenderer.esc(s);
    }
}
