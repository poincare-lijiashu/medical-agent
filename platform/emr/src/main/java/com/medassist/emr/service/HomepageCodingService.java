package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.domain.EmrModel.CodingQueueItem;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HomepageCoding;
import com.medassist.emr.domain.EmrModel.IcdDiagnosis;
import com.medassist.emr.domain.EmrModel.IcdProcedure;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.CodingDiagnosisReq;
import com.medassist.emr.web.EmrDtos.CodingProcedureReq;
import com.medassist.emr.web.EmrDtos.CodingSummary;
import com.medassist.emr.web.EmrDtos.CodingView;
import com.medassist.emr.web.EmrDtos.SaveCodingReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 病案编码工作台（G4）。
 *
 * <p>流程：首页已提交（SUBMITTED）→ 编码员保存草稿（CODING）→ 提交编码（CODED）
 * → 回写首页诊断/手术快照 → 可归档。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>编码只能引用 ICD 字典内且启用的编码，名称由服务端回填；</li>
 *   <li>编码提交时主诊断必须且只能有一个（DRG 入组与首页上报都以此为锚点）；</li>
 *   <li>编码完成（CODED）才能回写首页并归档，数据库触发器与归档服务双重校验。</li>
 * </ol>
 */
@Service
public class HomepageCodingService {

    private final EmrRepository repo;
    private final ObjectMapper objectMapper;

    public HomepageCodingService(EmrRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 待编码队列（已提交且编码未完成）；患者号按 phi:read 掩码。 */
    public List<CodingQueueItem> queue(String keyword, Integer limit, DataScope scope) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        return repo.listCodingQueue(keyword, size).stream()
                .map(item -> new CodingQueueItem(item.homepageId(), item.homepageNo(),
                        scope.maskPatientNo(item.patientNo()), item.patientNameMasked(),
                        item.deptName(), item.dischargedAt(), item.stayDays(),
                        item.codingStatus(), item.coder(), item.codedAt(),
                        item.homepageVersion(), item.codingVersion()))
                .toList();
    }

    public CodingSummary summary() {
        return new CodingSummary(repo.countPendingCoding(), repo.countCodedHomepages(),
                repo.countIcdDiagnoses(), repo.countIcdProcedures());
    }

    /** 编码详情：无编码记录时以临床快照作为初始值，编码员在此基础上确认/调整。 */
    public CodingView detail(UUID homepageId, DataScope scope) {
        Homepage homepage = repo.findHomepage(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        Optional<HomepageCoding> coding = repo.findCoding(homepageId);
        String diagnosesJson = coding.map(HomepageCoding::diagnoses).orElse(homepage.diagnoses());
        String proceduresJson = coding.map(HomepageCoding::procedures).orElse(homepage.procedures());
        return new CodingView(homepage.id(), homepage.homepageNo(),
                scope.maskPatientNo(homepage.patientNo()), maskName(homepage.patientName()),
                homepage.deptName(), homepage.dischargedAt(), homepage.stayDays(),
                homepage.status(), coding.map(HomepageCoding::status).orElse("PENDING"),
                coding.map(HomepageCoding::coder).orElse(null),
                coding.map(HomepageCoding::codedAt).orElse(null),
                coding.map(HomepageCoding::note).orElse(null),
                readJsonList(diagnosesJson), readJsonList(proceduresJson),
                readJsonList(homepage.diagnoses()),
                homepage.version(), coding.map(HomepageCoding::version).orElse(0L));
    }

    @Transactional
    public CodingView save(UUID homepageId, SaveCodingReq req, String actor, DataScope scope) {
        Homepage homepage = repo.findHomepage(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.SUBMITTED.equals(homepage.status())) {
            throw new BusinessException(ErrorCode.CODING_STATUS_INVALID,
                    "只有已提交的病案首页可以编码，当前：" + homepage.status());
        }
        Built built = build(req);
        Optional<HomepageCoding> existing = repo.findCoding(homepageId);
        if (existing.isEmpty()) {
            if (req.version() != 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "编码记录不存在或已被重置，请刷新后重试");
            }
            repo.insertCoding(new HomepageCoding(UUID.randomUUID(), homepageId,
                    HomepageCoding.CODING, actor, null, blankToNull(req.note()),
                    built.diagnosesJson(), built.proceduresJson(), 0L, Instant.now(), Instant.now()));
        } else {
            HomepageCoding coding = existing.get();
            if (coding.coded()) {
                throw new BusinessException(ErrorCode.CODING_STATUS_INVALID,
                        "该病案已完成编码，如需调整请由病案室走病案修改流程");
            }
            if (!repo.updateCodingDraft(homepageId, built.diagnosesJson(), built.proceduresJson(),
                    blankToNull(req.note()), actor, req.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "编码已被他人修改，请刷新后重试");
            }
        }
        audit(actor, "CODING_SAVE", homepageId.toString(), detail()
                .put("homepageNo", homepage.homepageNo())
                .put("diagnoses", built.diagnosisCount())
                .put("procedures", built.procedureCount())
                .put("primary", built.primaryCount())
                .build());
        return detail(homepageId, scope);
    }

    /**
     * 提交编码：CODING → CODED，并把编码结果回写首页诊断/手术快照。
     *
     * <p>已 CODED 时幂等返回，不重复回写（避免版本号被无意义推进）。
     */
    @Transactional
    public CodingView submit(UUID homepageId, String actor, DataScope scope) {
        Homepage homepage = repo.lockHomepage(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.SUBMITTED.equals(homepage.status())) {
            throw new BusinessException(ErrorCode.CODING_STATUS_INVALID,
                    "只有已提交的病案首页可以提交编码，当前：" + homepage.status());
        }
        HomepageCoding coding = repo.findCoding(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CODING_NOT_FOUND));
        if (coding.coded()) {
            return detail(homepageId, scope);
        }
        List<Map<String, Object>> diagnoses = readJsonList(coding.diagnoses());
        long primary = diagnoses.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("primary")))
                .count();
        if (diagnoses.isEmpty()) {
            throw new BusinessException(ErrorCode.CODING_INVALID, "至少需要一条诊断编码");
        }
        if (primary != 1) {
            throw new BusinessException(ErrorCode.CODING_INVALID,
                    "主诊断必须且只能有一个，当前 " + primary + " 个");
        }
        if (!repo.markCoded(homepageId, actor, coding.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "编码已被他人修改，请刷新后重试");
        }
        if (!repo.applyCodedSnapshot(homepageId, coding.diagnoses(), coding.procedures(), actor,
                homepage.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "病案首页已被他人修改（可能已归档），编码结果未能回写");
        }
        audit(actor, "CODING_SUBMIT", homepageId.toString(), detail()
                .put("homepageNo", homepage.homepageNo())
                .put("diagnoses", diagnoses.size())
                .put("procedures", readJsonList(coding.procedures()).size())
                .build());
        return detail(homepageId, scope);
    }

    // ---------------------------------------------------------------- 内部

    private Built build(SaveCodingReq req) {
        if (req.diagnoses() == null || req.diagnoses().isEmpty()) {
            throw new BusinessException(ErrorCode.CODING_INVALID, "至少需要一条诊断编码");
        }
        Set<String> codes = new LinkedHashSet<>();
        int primaryCount = 0;
        for (CodingDiagnosisReq line : req.diagnoses()) {
            String code = line.code().trim();
            if (!codes.add(code)) {
                throw new BusinessException(ErrorCode.CODING_INVALID, "诊断编码重复：" + code);
            }
            if (line.primary()) {
                primaryCount++;
            }
        }
        if (primaryCount > 1) {
            throw new BusinessException(ErrorCode.CODING_INVALID, "主诊断只能有一个");
        }
        Map<String, IcdDiagnosis> dict = repo.findIcdDiagnoses(codes).stream()
                .collect(Collectors.toMap(IcdDiagnosis::code, Function.identity()));
        List<Map<String, Object>> diagnoses = new ArrayList<>(codes.size());
        for (CodingDiagnosisReq line : req.diagnoses()) {
            String code = line.code().trim();
            IcdDiagnosis item = dict.get(code);
            if (item == null || !item.enabled()) {
                throw new BusinessException(ErrorCode.ICD_ITEM_NOT_FOUND,
                        "诊断编码 " + code + " 不存在或已停用");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.code());
            row.put("name", item.name());
            row.put("primary", line.primary());
            diagnoses.add(row);
        }

        List<CodingProcedureReq> procReqs = req.procedures() == null ? List.of() : req.procedures();
        Set<String> procCodes = new LinkedHashSet<>();
        for (CodingProcedureReq line : procReqs) {
            String code = line.code().trim();
            if (!procCodes.add(code)) {
                throw new BusinessException(ErrorCode.CODING_INVALID, "手术操作编码重复：" + code);
            }
        }
        Map<String, IcdProcedure> procDict = repo.findIcdProcedures(procCodes).stream()
                .collect(Collectors.toMap(IcdProcedure::code, Function.identity()));
        List<Map<String, Object>> procedures = new ArrayList<>(procReqs.size());
        for (CodingProcedureReq line : procReqs) {
            String code = line.code().trim();
            IcdProcedure item = procDict.get(code);
            if (item == null || !item.enabled()) {
                throw new BusinessException(ErrorCode.ICD_ITEM_NOT_FOUND,
                        "手术操作编码 " + code + " 不存在或已停用");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.code());
            row.put("name", item.name());
            if (line.date() != null) {
                row.put("date", line.date().toString());
            }
            putIfPresent(row, "surgeon", line.surgeon());
            putIfPresent(row, "anesthesia", line.anesthesia());
            putIfPresent(row, "incisionLevel", line.incisionLevel());
            procedures.add(row);
        }
        return new Built(writeJson(diagnoses), writeJson(procedures), diagnoses.size(),
                procedures.size(), primaryCount);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "编码内容序列化失败");
        }
    }

    private void putIfPresent(Map<String, Object> row, String key, String value) {
        if (value != null && !value.isBlank()) {
            row.put(key, value.trim());
        }
    }

    private String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.charAt(0) + "**";
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Detail detail() {
        return new Detail();
    }

    private final class Detail {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Detail put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        String build() {
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JacksonException e) {
                return "{\"_detail_serialize_failed\":true}";
            }
        }
    }

    private void audit(String actor, String action, String targetId, String detailJson) {
        repo.logChange(actor, action, "HOMEPAGE_CODING", targetId, detailJson);
    }

    /** 校验并规整后的编码内容。 */
    private record Built(String diagnosesJson, String proceduresJson, int diagnosisCount,
                         int procedureCount, int primaryCount) {
    }
}
