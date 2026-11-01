package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 服务间就诊画像查询（仅内网直连，不经过网关）。
 *
 * <p>病历域创建病历时需要两件事：① 该就诊属于该患者（防止病历挂到别人的就诊上）；
 * ② 住院患者的入院时间（书写时限质控）。两者都从临床域取，避免跨 schema 直查。
 *
 * <p>安全边界与其它内部接口一致：网关对 {@code /api/v2/*&#47;internal/**} 直接 404，
 * 且要求共享令牌（跳过令牌一律拒绝）。
 */
@RestController
@RequestMapping("/internal/visits")
public class InternalVisitController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ClinicalRepository repo;
    private final String token;

    public InternalVisitController(ClinicalRepository repo,
                                   @Value("${clinical.internal-api-token:${clinical.internal-token:}}")
                                   String token) {
        this.repo = repo;
        this.token = token == null ? "" : token;
    }

    /** 就诊画像：归属患者、就诊类型与日期、住院起止时间（门诊为空）。 */
    @GetMapping("/{visitId}")
    public Map<String, Object> visit(@PathVariable UUID visitId,
                                     @RequestHeader(value = "X-Internal-Token", required = false)
                                     String provided) {
        requireToken(provided);
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "就诊记录不存在"));
        Optional<Admission> admission = repo.findLatestAdmissionByVisit(visitId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitId", visit.id());
        body.put("patientId", visit.patientId());
        body.put("visitType", visit.visitType());
        body.put("visitDate", visit.visitDate() == null ? null : visit.visitDate().toString());
        body.put("admittedAt", admission.map(Admission::admittedAt).map(ISO::format).orElse(null));
        body.put("dischargedAt", admission.map(Admission::dischargedAt)
                .map(ISO::format).orElse(null));
        return body;
    }

    /**
     * 就诊诊断（医保清单/病案首页取值）：主诊断排在最前，按录入顺序返回。
     *
     * <p>只返回编码、名称与主诊断标记；不暴露录入人等内部字段。
     */
    @GetMapping("/{visitId}/diagnoses")
    public Map<String, Object> diagnoses(@PathVariable UUID visitId,
                                         @RequestHeader(value = "X-Internal-Token", required = false)
                                         String provided) {
        requireToken(provided);
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "就诊记录不存在"));
        List<Map<String, Object>> items = repo.listVisitDiagnoses(visitId).stream()
                .map(d -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", d.code());
                    row.put("name", d.name());
                    row.put("primary", d.primaryFlag());
                    return row;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitId", visitId);
        body.put("patientId", visit.patientId());
        body.put("diagnoses", items);
        return body;
    }

    /**
     * 病案首页数据源：一次取齐"住院信息 + 科室/病区/床位名称 + 出院诊断 + 费用汇总"。
     *
     * <p>为什么要合并成一个接口：病历域生成首页时要固化一份快照，
     * 若分多次调用，两次调用之间诊断或费用发生变化会得到自相矛盾的首页。
     * 本接口只做只读快照，**不判断是否允许生成首页**（必须已出院等业务规则由调用方执行），
     * 这样临床域不需要知道病案域的流程规则。
     */
    @GetMapping("/{visitId}/homepage-source")
    public Map<String, Object> homepageSource(@PathVariable UUID visitId,
                                              @RequestHeader(value = "X-Internal-Token", required = false)
                                              String provided) {
        requireToken(provided);
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "就诊记录不存在"));
        Optional<Admission> admission = repo.findLatestAdmissionByVisit(visitId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitId", visit.id());
        body.put("visitNo", visit.visitNo());
        body.put("visitType", visit.visitType());
        body.put("visitStatus", visit.status());
        body.put("visitDate", visit.visitDate() == null ? null : visit.visitDate().toString());
        body.put("patientId", visit.patientId());
        body.put("patientNo", visit.patientNo());
        body.put("deptId", visit.deptId());
        body.put("deptName", visit.deptId() == null ? null
                : repo.findDept(visit.deptId()).map(d -> d.name()).orElse(null));

        body.put("admissionId", admission.map(Admission::id).orElse(null));
        body.put("admissionNo", admission.map(Admission::admissionNo).orElse(null));
        body.put("admissionStatus", admission.map(Admission::status).orElse(null));
        body.put("admittedAt", admission.map(Admission::admittedAt).map(ISO::format).orElse(null));
        body.put("dischargedAt", admission.map(Admission::dischargedAt).map(ISO::format).orElse(null));
        body.put("wardId", admission.map(Admission::wardId).orElse(null));
        body.put("wardName", admission.map(Admission::wardId)
                .map(id -> repo.findWard(id).map(w -> w.name()).orElse(null)).orElse(null));
        body.put("bedId", admission.map(Admission::bedId).orElse(null));
        body.put("bedNo", admission.map(Admission::bedId)
                .map(id -> repo.findBed(id).map(b -> b.bedNo()).orElse(null)).orElse(null));
        body.put("attendingDoctor", admission.map(Admission::attendingDoctor).orElse(null));

        List<Map<String, Object>> diagnoses = repo.listVisitDiagnoses(visitId).stream()
                .map(d -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", d.code());
                    row.put("name", d.name());
                    row.put("primary", d.primaryFlag());
                    return row;
                })
                .toList();
        body.put("diagnoses", diagnoses);

        Map<String, BigDecimal> feeSummary = repo.sumChargeByCategory(visitId);
        body.put("feeSummary", feeSummary);

        // 手术操作：只取"已完成"手术记录（未完成的手术不能进病案，否则虚增手术操作）
        List<Map<String, Object>> procedures = repo.listCompletedSurgeryCases(visitId).stream()
                .map(s -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", s.operationCode());
                    row.put("name", s.operationName());
                    row.put("date", s.startedAt() == null ? null
                            : java.time.LocalDate.ofInstant(s.startedAt(),
                                    com.medassist.common.time.BusinessTime.zone()).toString());
                    row.put("surgeon", s.surgeon());
                    row.put("anesthesia", s.anesthesiaType());
                    row.put("incisionLevel", s.incisionLevel());
                    return row;
                })
                .toList();
        body.put("procedures", procedures);
        body.put("totalAmount", feeSummary.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return body;
    }

    private void requireToken(String provided) {
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
        if (provided == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
    }
}
