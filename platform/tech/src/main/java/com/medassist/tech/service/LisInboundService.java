package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.LisInboundMessage;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.hl7.HL7OruParser;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.TechDtos.CriticalLine;
import com.medassist.tech.web.TechDtos.OruResult;
import com.medassist.tech.web.TechDtos.WriteReportReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LIS 入站 ORU^R01 结果回写（N6 第一批）。
 *
 * <p>处理口径：
 * <ol>
 *   <li>按 MSH-10 幂等：同一消息重复投递只处理一次，返回首次处理结果（duplicate=true）；</li>
 *   <li>申请必须存在且处于可回写状态；已签发/已关闭/已取消 → IGNORED（不报错，避免 LIS 无限重试）；</li>
 *   <li>回写只落到**报告草稿**：签发仍由上级技师/医师完成（不绕过审核签发流程）；</li>
 *   <li>HH/LL 异常标志自动生成危急值（PENDING），必须由临床确认闭环，跨域不可省；</li>
 *   <li>处理失败也落库（status=FAILED + 原因），接口返回 200 让 LIS 有据可查，不用靠 4xx 猜。</li>
 * </ol>
 */
@Service
public class LisInboundService {

    private final TechRepository repo;
    private final ApplicationService applicationService;
    private final ReportService reportService;
    private final ObjectMapper objectMapper;

    public LisInboundService(TechRepository repo, ApplicationService applicationService,
                             ReportService reportService, ObjectMapper objectMapper) {
        this.repo = repo;
        this.applicationService = applicationService;
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收一条 HL7 v2 ORU 报文。
     *
     * <p>报文本身不可解析（缺控制 ID/申请号/OBX）时返回 422 且不留库——没有可靠的幂等键，
     * 留库只会制造无法去重的脏数据。
     */
    @Transactional
    public OruResult ingest(String raw) {
        HL7OruParser.Oru oru = HL7OruParser.parse(raw);
        if (!oru.valid()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "HL7 消息不可用：" + String.join("；", oru.errors()));
        }
        Optional<LisInboundMessage> existing = repo.findLisMessageByControlId(oru.controlId());
        if (existing.isPresent()) {
            return toResult(existing.get(), true);
        }
        String actor = oru.sendingApp().isBlank() ? "lis" : oru.sendingApp();
        String hash = sha256(raw);

        String status;
        String error;
        UUID applicationId = null;
        UUID reportId = null;
        Optional<Application> found = repo.findApplicationByNo(oru.applicationNo());
        if (found.isEmpty()) {
            status = LisInboundMessage.FAILED;
            error = "申请号不存在：" + oru.applicationNo();
        } else {
            Application app = found.get();
            applicationId = app.id();
            ProcessResult processed = process(app, oru, actor);
            status = processed.status();
            error = processed.error();
            reportId = processed.reportId();
        }

        LisInboundMessage message = new LisInboundMessage(UUID.randomUUID(), oru.controlId(),
                oru.messageType(), oru.sendingApp(), oru.applicationNo(), applicationId,
                reportId, status, error, raw, hash, actor, null);
        if (!repo.insertLisMessage(message)) {
            // 并发下另一请求已抢先落库：以库里那条为准，保证两端看到同一结果
            return toResult(repo.findLisMessageByControlId(oru.controlId()).orElse(message), true);
        }
        audit(actor, "LIS_ORU_" + status, oru.controlId(), Map.of(
                "applicationNo", oru.applicationNo(),
                "applicationId", applicationId == null ? "" : applicationId.toString(),
                "reportId", reportId == null ? "" : reportId.toString(),
                "observations", oru.observations().size(),
                "messageHash", hash));
        LisInboundMessage saved = repo.findLisMessageByControlId(oru.controlId()).orElse(message);
        return toResult(saved, false);
    }

    private record ProcessResult(String status, String error, UUID reportId) {
    }

    private ProcessResult process(Application app, HL7OruParser.Oru oru, String actor) {
        if (Application.CLOSED.equals(app.status()) || Application.CANCELLED.equals(app.status())
                || Application.REPORTED.equals(app.status())) {
            return new ProcessResult(LisInboundMessage.IGNORED,
                    "申请当前状态为 " + app.status() + "，无需回写", null);
        }
        try {
            Application current = app;
            if (!Application.IN_PROGRESS.equals(current.status())) {
                // 检验需先接收标本、检查需先开始执行；两种合法起点都走既有状态机
                applicationService.start(current.id(), actor);
                current = repo.findApplication(current.id()).orElse(current);
            }
            WriteReportReq req = toReportRequest(app, oru);
            Optional<Report> existing = repo.findCurrentReport(current.id());
            if (existing.isPresent()) {
                Report report = existing.get();
                if (!Report.DRAFT.equals(report.status())) {
                    return new ProcessResult(LisInboundMessage.IGNORED,
                            "报告已签发（" + report.status() + "），不接受自动覆盖", report.id());
                }
                reportService.rewrite(report.id(), req, actor);
                return new ProcessResult(LisInboundMessage.PROCESSED, null, report.id());
            }
            var written = reportService.write(current.id(), req, actor);
            return new ProcessResult(LisInboundMessage.PROCESSED, null, written.report().id());
        } catch (BusinessException e) {
            // 业务校验失败（状态不可回写、报告冲突等）：落库为 FAILED，便于 LIS 侧排查与重放
            return new ProcessResult(LisInboundMessage.FAILED,
                    "回写失败：" + e.getMessage(), null);
        }
    }

    /** 把 OBX 列表转成报告草稿请求：所见逐行拼接，结论汇总异常项，HH/LL 生成危急值。 */
    private WriteReportReq toReportRequest(Application app, HL7OruParser.Oru oru) {
        StringBuilder findings = new StringBuilder();
        List<String> abnormal = new ArrayList<>();
        List<CriticalLine> criticals = new ArrayList<>();
        for (HL7OruParser.Observation ob : oru.observations()) {
            String name = ob.itemName() == null || ob.itemName().isBlank()
                    ? ob.itemCode() : ob.itemName();
            findings.append(name).append("：").append(nvl(ob.value()));
            if (ob.unit() != null && !ob.unit().isBlank()) {
                findings.append(" ").append(ob.unit());
            }
            if (ob.referenceRange() != null && !ob.referenceRange().isBlank()) {
                findings.append("（参考范围 ").append(ob.referenceRange()).append("）");
            }
            findings.append('\n');
            if (ob.abnormal()) {
                abnormal.add(name);
            }
            if (ob.critical()) {
                String resultValue = nvl(ob.value())
                        + (ob.unit() == null || ob.unit().isBlank() ? "" : " " + ob.unit());
                criticals.add(new CriticalLine(name, trim(resultValue, 64),
                        trim(ob.referenceRange(), 64), "CRITICAL", null));
            }
        }
        String conclusion = abnormal.isEmpty()
                ? "结果已在所见中列明，未见明显异常"
                : "异常项：" + String.join("、", abnormal);
        return new WriteReportReq(trim(findings.toString().trim(), 4000),
                trim(conclusion, 4000), criticals, null, null);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static OruResult toResult(LisInboundMessage m, boolean duplicate) {
        return new OruResult(m.id(), m.messageControlId(), m.status(), m.applicationNo(),
                m.applicationId(), m.reportId(), m.errorMessage(), duplicate);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "LIS_MESSAGE", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "LIS_MESSAGE", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
