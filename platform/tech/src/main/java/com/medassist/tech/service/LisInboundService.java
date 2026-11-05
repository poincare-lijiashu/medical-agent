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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate tx;

    public LisInboundService(TechRepository repo, ApplicationService applicationService,
                             ReportService reportService, ObjectMapper objectMapper,
                             PlatformTransactionManager txManager) {
        this.repo = repo;
        this.applicationService = applicationService;
        this.reportService = reportService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 接收一条 HL7 v2 ORU 报文。
     *
     * <p>报文本身不可解析（缺控制 ID/申请号/OBX）时返回 422 且不留库——没有可靠的幂等键，
     * 留库只会制造无法去重的脏数据。
     *
     * <p>顺序（G67，评审 §3.5）：先 INSERT 一条 PROCESSING 占位并**单独提交**，再处理副作用。
     * 唯一索引让并发同 control_id 只有一个请求继续写报告/危急值，另一个按重复投递返回。
     *
     * <p>三个短事务而非一个大事务：占位必须先提交才能形成互斥；副作用失败时整段回滚，
     * 再把 FAILED 另行落库——否则内层事务被标记 rollback-only，外层提交会变成 500。
     */
    public OruResult ingest(String raw) {
        HL7OruParser.Oru oru = HL7OruParser.parse(raw);
        if (!oru.valid()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "HL7 消息不可用：" + String.join("；", oru.errors()));
        }
        String actor = oru.sendingApp().isBlank() ? "lis" : oru.sendingApp();
        String hash = sha256(raw);
        UUID messageId = UUID.randomUUID();
        Boolean claimed = tx.execute(status -> repo.insertLisMessage(
                new LisInboundMessage(messageId, oru.controlId(), oru.messageType(),
                        oru.sendingApp(), oru.applicationNo(), null, null,
                        LisInboundMessage.PROCESSING, null, raw, hash, actor, null)));
        if (!Boolean.TRUE.equals(claimed)) {
            // 撞唯一键：首个请求已提交（或正在提交），读库里的最终结果按重复投递返回
            LisInboundMessage existing = repo.findLisMessageByControlId(oru.controlId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.TECH_LIS_MESSAGE_NOT_FOUND));
            return toResult(existing, true);
        }
        ProcessResult processed = processInTransaction(oru, actor);
        saveResult(messageId, processed, actor, "LIS_ORU_" + processed.status(),
                Map.of("applicationNo", oru.applicationNo(),
                        "applicationId", processed.applicationId() == null ? ""
                                : processed.applicationId().toString(),
                        "reportId", processed.reportId() == null ? ""
                                : processed.reportId().toString(),
                        "observations", oru.observations().size(),
                        "messageHash", hash));
        return toResult(current(messageId), false);
    }

    /**
     * 重放一条已落库消息（G67，评审 §3.6）。
     *
     * <p>FAILED / IGNORED 才能重放：瞬时故障、申请事后补建、报告退回草稿都可能让同一报文
     * 变得可处理。已 PROCESSED 的消息直接返回既有结果，不重复写报告。
     */
    public OruResult replay(UUID messageId, String actor) {
        LisInboundMessage message = current(messageId);
        if (LisInboundMessage.PROCESSED.equals(message.status())) {
            return toResult(message, true);
        }
        Boolean claimed = tx.execute(status ->
                repo.claimLisMessageForReplay(messageId, message.status()));
        if (!Boolean.TRUE.equals(claimed)) {
            throw new BusinessException(ErrorCode.CONFLICT, "消息正在被其他请求处理，请刷新后重试");
        }
        HL7OruParser.Oru oru = HL7OruParser.parse(message.rawMessage());
        ProcessResult processed = oru.valid()
                ? processInTransaction(oru, actor)
                : new ProcessResult(LisInboundMessage.FAILED,
                        "报文无法解析：" + String.join("；", oru.errors()), null, null);
        saveResult(messageId, processed, actor, "LIS_ORU_REPLAY",
                Map.of("previousStatus", message.status(),
                        "status", processed.status(),
                        "messageControlId", message.messageControlId()));
        return toResult(current(messageId), false);
    }

    /** 消息关联的申请主键；申请号不存在的失败消息为空。 */
    public UUID applicationIdOf(UUID messageId) {
        return current(messageId).applicationId();
    }

    private LisInboundMessage current(UUID messageId) {
        return repo.findLisMessage(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_LIS_MESSAGE_NOT_FOUND));
    }

    /**
     * 在一个独立事务里跑副作用。
     *
     * <p>失败时该事务整体回滚（报告草稿、危急值都不留残影），把异常转成 FAILED 结果由
     * 调用方另行落库；否则内层事务被标记 rollback-only，外层提交必然 500。
     */
    private ProcessResult processInTransaction(HL7OruParser.Oru oru, String actor) {
        try {
            return tx.execute(status -> process(oru, actor));
        } catch (RuntimeException e) {
            return new ProcessResult(LisInboundMessage.FAILED, "回写失败：" + describe(e),
                    null, null);
        }
    }

    private void saveResult(UUID messageId, ProcessResult processed, String actor, String action,
                            Map<String, Object> detail) {
        Boolean updated = tx.execute(status -> repo.updateLisMessageResult(messageId,
                processed.status(), processed.error(), processed.applicationId(),
                processed.reportId()));
        if (!Boolean.TRUE.equals(updated)) {
            throw new BusinessException(ErrorCode.CONFLICT, "消息状态已变化，请刷新后重试");
        }
        audit(actor, action, messageId.toString(), detail);
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record ProcessResult(String status, String error, UUID applicationId, UUID reportId) {
    }

    private ProcessResult process(HL7OruParser.Oru oru, String actor) {
        Optional<Application> found = repo.findApplicationByNo(oru.applicationNo());
        if (found.isEmpty()) {
            return new ProcessResult(LisInboundMessage.FAILED,
                    "申请号不存在：" + oru.applicationNo(), null, null);
        }
        Application app = found.get();
        return process(app, oru, actor);
    }

    private ProcessResult process(Application app, HL7OruParser.Oru oru, String actor) {
        if (Application.CLOSED.equals(app.status()) || Application.CANCELLED.equals(app.status())
                || Application.REPORTED.equals(app.status())) {
            return new ProcessResult(LisInboundMessage.IGNORED,
                    "申请当前状态为 " + app.status() + "，无需回写", app.id(), null);
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
                            "报告已签发（" + report.status() + "），不接受自动覆盖",
                            current.id(), report.id());
                }
                reportService.rewrite(report.id(), req, actor);
                return new ProcessResult(LisInboundMessage.PROCESSED, null, current.id(),
                        report.id());
            }
            var written = reportService.write(current.id(), req, actor);
            return new ProcessResult(LisInboundMessage.PROCESSED, null, current.id(),
                    written.report().id());
        } catch (BusinessException e) {
            // 业务校验失败（状态不可回写、报告冲突等）：落库为 FAILED，便于 LIS 侧排查与重放
            return new ProcessResult(LisInboundMessage.FAILED,
                    "回写失败：" + e.getMessage(), app.id(), null);
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
