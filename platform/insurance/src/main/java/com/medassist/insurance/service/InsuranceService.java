package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.client.ClinicalClient;
import com.medassist.insurance.client.PatientClient;
import com.medassist.insurance.domain.InsuranceModel.BillLine;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.MemberHistory;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.BillView;
import com.medassist.insurance.web.InsuranceDtos.CatalogChange;
import com.medassist.insurance.web.InsuranceDtos.CatalogImportResult;
import com.medassist.insurance.web.InsuranceDtos.ImportResult;
import com.medassist.insurance.web.InsuranceDtos.ItemReq;
import com.medassist.insurance.web.InsuranceDtos.MappingReq;
import com.medassist.insurance.web.InsuranceDtos.MemberReq;
import com.medassist.insurance.web.InsuranceDtos.ReconcileView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 医保目录、映射、参保登记与结算清单测算。
 *
 * <p>结算口径（本地测算模型，真实结算以省局接口为准）：
 * <ol>
 *   <li>甲类：全额纳入；乙类：先自付 selfPayRatio，其余纳入；丙类：全额自费；</li>
 *   <li>未映射或映射已停用的收费项目按丙类处理，并在明细里标记 unmapped，便于对账；</li>
 *   <li>纳入金额扣除起付线后，按参保报销比例计算统筹支付，并以**年度剩余封顶额度**与总额双重截断；</li>
 *   <li>个人自付 = 总费用 − 统筹支付；</li>
 *   <li>参保有效期按就诊业务日期校验，年度额度按清单确认时间归属自然年。</li>
 * </ol>
 */
@Service
public class InsuranceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** 目录文件上限（与 multipart 配置一致，双重保护）。 */
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    /** 预览结果最多返回的变更条数（完整数量见 created/updated）。 */
    private static final int MAX_CHANGES_RETURNED = 50;

    private final InsuranceRepository repo;
    private final PatientClient patientClient;
    private final ClinicalClient clinicalClient;
    private final ObjectMapper objectMapper;

    public InsuranceService(InsuranceRepository repo, PatientClient patientClient,
                            ClinicalClient clinicalClient, ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.clinicalClient = clinicalClient;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 医保目录

    public List<InsuranceItem> listItems(String category, Boolean enabled, String q, Integer limit) {
        String normalized = (category == null || category.isBlank()) ? null : category.trim().toUpperCase();
        return repo.listItems(normalized, enabled, likePattern(q),
                limit == null || limit <= 0 ? 100 : Math.min(limit, 500));
    }

    /**
     * 新增/更新目录项目。
     *
     * <p>请求带 {@code version} 时按乐观锁更新（版本不一致 409），批量导入等强制覆盖场景不带。
     * 审计记录 old→new 对照（目录项直接决定报销口径，只记新值无法追查"改前是什么"）。
     */
    @Transactional
    public InsuranceItem createItem(ItemReq req, String actor) {
        InsuranceItem item = toItem(req);
        Optional<InsuranceItem> before = repo.findItemByCode(item.insuranceCode());
        if (repo.upsertItem(item, req.version()).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "医保目录项目已被他人修改（当前版本与提交版本不一致），请刷新后重试");
        }
        InsuranceItem saved = repo.findItemByCode(item.insuranceCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("insuranceCode", saved.insuranceCode());
        detail.put("before", before.map(InsuranceService::itemSnapshot).orElse(null));
        detail.put("after", itemSnapshot(saved));
        audit(actor, "ITEM_UPSERT", "INSURANCE_ITEM", saved.insuranceCode(), detail);
        return saved;
    }

    @Transactional
    public ImportResult importItems(List<ItemReq> items, String actor) {
        List<String> codes = new ArrayList<>();
        for (ItemReq req : items) {
            InsuranceItem item = toItem(req);
            // 批量导入是"以文件为准"的强制覆盖，不带版本条件
            repo.upsertItem(item, null);
            codes.add(item.insuranceCode());
        }
        audit(actor, "ITEM_IMPORT", "INSURANCE_ITEM", "batch", Map.of(
                "count", codes.size(), "codes", codes.stream().limit(20).toList()));
        return new ImportResult(codes.size(), codes);
    }

    /**
     * 目录文件导入（CSV / XML）：先差异预览，确认后再落库。
     *
     * <p>商业口径：
     * <ul>
     *   <li>预览与落库用同一套比对逻辑，避免"看到 3 条变更、实际写了 5 条"；</li>
     *   <li>**幂等**：内容未变的目录项不写库、不升版本，同一文件重复 apply 不会产生噪音审计；</li>
     *   <li>文件未给出 {@code enabled} 时保留库里原值（不把停用项隐式复活），新建默认启用；</li>
     *   <li>库里启用但文件未覆盖的目录项**只统计不自动停用**（停用必须显式操作）；</li>
     *   <li>任一解析/校验错误整体拒绝，不做"导一半"。</li>
     * </ul>
     */
    @Transactional
    public CatalogImportResult importCatalogFile(String fileName, byte[] content, String mode,
                                                 String actor) {
        String normalizedMode = (mode == null || mode.isBlank())
                ? "PREVIEW" : mode.trim().toUpperCase(Locale.ROOT);
        if (!"PREVIEW".equals(normalizedMode) && !"APPLY".equals(normalizedMode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "mode 只支持 preview 或 apply");
        }
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请上传非空的目录文件");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "目录文件不得超过 " + (MAX_FILE_BYTES / 1024 / 1024) + " MB");
        }
        CatalogFileParser.Parsed parsed = CatalogFileParser.parse(fileName, content);
        if (!parsed.errors().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "目录文件校验失败：" + String.join("；", parsed.errors()));
        }
        List<CatalogFileParser.Row> rows = parsed.rows();
        List<String> codes = rows.stream().map(CatalogFileParser.Row::code).toList();
        Map<String, InsuranceItem> existing = new LinkedHashMap<>();
        for (InsuranceItem item : repo.findItemsByCodes(codes)) {
            existing.put(item.insuranceCode(), item);
        }

        List<CatalogFileParser.Row> toCreate = new ArrayList<>();
        List<CatalogFileParser.Row> toUpdate = new ArrayList<>();
        List<CatalogChange> changes = new ArrayList<>();
        int unchanged = 0;
        for (CatalogFileParser.Row row : rows) {
            InsuranceItem current = existing.get(row.code());
            if (current == null) {
                // 新建乙类必须给出自付比例：文件漏列时按 0 落库会让统筹支付虚高（第四轮 MF-03）
                if ("B".equals(row.payCategory()) && row.selfPayRatio() == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "第 " + row.lineNo() + " 行：乙类（B）目录项必须提供自付比例（0~1）："
                                    + row.code());
                }
                toCreate.add(row);
                changes.add(new CatalogChange(row.code(), "CREATE", List.of()));
                continue;
            }
            // 更新路径同样要挡：库里 C 类无比例 → 文件改成 B 且未给比例时，
            // mergeItem 会把 null 兜成 0，测算按 0 全额纳入（第五轮 R5-M6，MF-03 的补漏）
            if ("B".equals(row.payCategory()) && row.selfPayRatio() == null
                    && current.selfPayRatio() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "第 " + row.lineNo() + " 行：乙类（B）目录项必须提供自付比例（0~1）："
                                + row.code() + "（库内该目录项也未设置比例）");
            }
            List<String> diff = changedFields(current, row);
            if (diff.isEmpty()) {
                unchanged++;
            } else {
                toUpdate.add(row);
                changes.add(new CatalogChange(row.code(), "UPDATE", diff));
            }
        }
        long missingInFile = repo.countEnabledItemsNotInCodes(codes);
        String digest = CatalogFileParser.sha256(content);

        if ("APPLY".equals(normalizedMode)) {
            for (CatalogFileParser.Row row : toCreate) {
                InsuranceItem item = mergeItem(row, null);
                repo.upsertItem(item, null);
                auditFileItem(actor, fileName, row.code(), null, item);
            }
            for (CatalogFileParser.Row row : toUpdate) {
                InsuranceItem before = existing.get(row.code());
                InsuranceItem item = mergeItem(row, before);
                repo.upsertItem(item, null);
                auditFileItem(actor, fileName, row.code(), before, item);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fileName", fileName == null ? "" : fileName);
            detail.put("sha256", digest);
            detail.put("total", rows.size());
            detail.put("created", toCreate.size());
            detail.put("updated", toUpdate.size());
            detail.put("unchanged", unchanged);
            detail.put("missingInFile", missingInFile);
            detail.put("warnings", parsed.warnings());
            audit(actor, "ITEM_FILE_IMPORT", "INSURANCE_ITEM", "file", detail);
        }

        boolean truncated = changes.size() > MAX_CHANGES_RETURNED;
        return new CatalogImportResult(normalizedMode, fileName, digest, rows.size(),
                toCreate.size(), toUpdate.size(), unchanged, (int) missingInFile, truncated,
                changes.stream().limit(MAX_CHANGES_RETURNED).toList(), parsed.warnings());
    }

    /** 文件值未给出的字段保留库里原值；新建时给默认值（比例 0、无限价、启用）。 */
    private InsuranceItem mergeItem(CatalogFileParser.Row row, InsuranceItem before) {
        BigDecimal ratio = row.selfPayRatio() != null
                ? row.selfPayRatio()
                : (before == null || before.selfPayRatio() == null ? BigDecimal.ZERO : before.selfPayRatio());
        BigDecimal limit = row.priceLimit() != null
                ? money(row.priceLimit())
                : (before == null ? null : before.priceLimit());
        boolean enabled = row.enabled() != null ? row.enabled() : (before == null || before.enabled());
        return new InsuranceItem(UUID.randomUUID(), row.code(), row.name(), row.category(),
                row.payCategory(), ratio, limit, enabled, 0L);
    }

    /** 与库里比对，返回变化字段名（只覆盖文件显式给出的字段，避免"缺列即清零"）。 */
    private static List<String> changedFields(InsuranceItem current, CatalogFileParser.Row row) {
        List<String> fields = new ArrayList<>();
        if (!current.name().equals(row.name())) {
            fields.add("name");
        }
        if (!current.category().equals(row.category())) {
            fields.add("category");
        }
        if (!current.payCategory().equals(row.payCategory())) {
            fields.add("payCategory");
        }
        if (row.selfPayRatio() != null && (current.selfPayRatio() == null
                || current.selfPayRatio().compareTo(row.selfPayRatio()) != 0)) {
            fields.add("selfPayRatio");
        }
        if (row.priceLimit() != null && (current.priceLimit() == null
                || current.priceLimit().compareTo(row.priceLimit()) != 0)) {
            fields.add("priceLimit");
        }
        if (row.enabled() != null && current.enabled() != row.enabled()) {
            fields.add("enabled");
        }
        return fields;
    }

    private void auditFileItem(String actor, String fileName, String code,
                               InsuranceItem before, InsuranceItem after) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fileName", fileName == null ? "" : fileName);
        detail.put("insuranceCode", code);
        detail.put("before", before == null ? null : itemSnapshot(before));
        detail.put("after", itemSnapshot(after));
        audit(actor, "ITEM_FILE_UPSERT", "INSURANCE_ITEM", code, detail);
    }

    @Transactional
    public InsuranceItem toggleItem(UUID id, boolean enabled, long version, String actor) {
        InsuranceItem item = repo.findItemById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND));
        if (!repo.setItemEnabled(id, enabled, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "目录项目已被他人修改，请刷新后重试");
        }
        audit(actor, "ITEM_TOGGLE", "INSURANCE_ITEM", item.insuranceCode(),
                Map.of("enabled", enabled, "insuranceCode", item.insuranceCode()));
        return repo.findItemByCode(item.insuranceCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 院内映射

    public List<ChargeMapping> listMappings(String q, Integer limit) {
        return repo.listMappings(likePattern(q), limit == null || limit <= 0 ? 100 : Math.min(limit, 500));
    }

    @Transactional
    public ChargeMapping upsertMapping(String chargeItemCode, MappingReq req, String actor) {
        String code = chargeItemCode.trim();
        repo.findItemByCode(req.insuranceCode().trim())
                .filter(InsuranceItem::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND,
                        "医保目录不存在或已停用：" + req.insuranceCode()));
        boolean enabled = req.enabled() == null || req.enabled();
        repo.upsertMapping(UUID.randomUUID(), code, req.insuranceCode().trim(), enabled);
        audit(actor, "MAPPING_UPSERT", "CHARGE_MAPPING", code, Map.of(
                "chargeItemCode", code, "insuranceCode", req.insuranceCode().trim(), "enabled", enabled));
        return repo.findMapping(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 参保登记

    /**
     * 参保登记（按 patientId UPSERT）。
     *
     * <p>两条硬规则：
     * <ol>
     *   <li>已停用的参保不能靠"不传 status 走默认 ACTIVE"复活——必须显式提交 {@code status=ACTIVE}，
     *       且会留痕为 ENABLE；</li>
     *   <li>每次写入都追加一条 {@code insurance_member_history}（CREATE/UPDATE/DISABLE/ENABLE），
     *       待遇参数改过什么可逐条追溯。</li>
     * </ol>
     */
    @Transactional
    public Member registerMember(MemberReq req, String actor) {
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());
        Optional<Member> existing = repo.findMemberByPatient(patient.id());
        existing.filter(old -> Member.DISABLED.equals(old.status()))
                .filter(old -> req.status() == null || !Member.ACTIVE.equals(req.status()))
                .ifPresent(old -> {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "该患者参保已停用；如需恢复请显式提交 status=ACTIVE（将留痕为重新启用）");
                });
        Member member = new Member(existing.map(Member::id).orElseGet(UUID::randomUUID),
                patient.id(), patient.patientNo(), req.insuranceType().trim(), req.region().trim(),
                blankToNull(req.personCategory()),
                req.deductible() == null ? ZERO : money(req.deductible()),
                req.reimbursementRatio() == null ? new BigDecimal("0.8000") : req.reimbursementRatio(),
                req.cap() == null ? new BigDecimal("300000.00") : money(req.cap()),
                req.effectiveFrom() == null ? BusinessTime.today() : req.effectiveFrom(),
                req.effectiveTo(),
                req.status() == null ? Member.ACTIVE : req.status(),
                existing.map(Member::version).orElse(0L));
        long expectedVersion = existing.map(Member::version).orElse(0L);
        if (repo.upsertMember(member, expectedVersion).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "参保信息已被他人修改，请刷新后重试");
        }
        Member saved = repo.findMemberByPatient(patient.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_MEMBER_NOT_FOUND));
        String changeType = changeTypeOf(existing.orElse(null), saved);
        repo.appendMemberHistory(new MemberHistory(UUID.randomUUID(), saved.id(), saved.patientId(),
                saved.patientNo(), saved.insuranceType(), saved.region(), saved.personCategory(),
                saved.deductible(), saved.reimbursementRatio(), saved.cap(),
                saved.effectiveFrom(), saved.effectiveTo(), saved.status(), saved.version(),
                changeType, actor, null, null));
        audit(actor, "MEMBER_UPSERT", "MEMBER", patient.id().toString(), Map.of(
                // P1-S4：审计明细不留明文患者号（before/after 只放待遇参数，不含身份信息）
                "patientNo", com.medassist.common.security.Masks.patientNo(patient.patientNo()),
                "changeType", changeType,
                "before", existing.map(InsuranceService::memberSnapshot)
                        .map(snapshot -> (Object) snapshot).orElse("NEW"),
                "after", memberSnapshot(saved)));
        return saved;
    }

    /** 变更类型：新建 / 启停 / 一般修改。 */
    private static String changeTypeOf(Member before, Member after) {
        if (before == null) {
            return MemberHistory.CREATE;
        }
        if (!before.status().equals(after.status())) {
            return Member.DISABLED.equals(after.status())
                    ? MemberHistory.DISABLE : MemberHistory.ENABLE;
        }
        return MemberHistory.UPDATE;
    }

    /** 参保变更历史（最新在前）。 */
    public List<MemberHistory> memberHistory(UUID patientId, Integer limit) {
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listMemberHistory(patientId, size);
    }

    /** 目录项快照（审计 old→new 用）。 */
    private static Map<String, Object> itemSnapshot(InsuranceItem item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", item.name());
        snapshot.put("category", item.category());
        snapshot.put("payCategory", item.payCategory());
        snapshot.put("selfPayRatio", item.selfPayRatio() == null ? null : item.selfPayRatio().toPlainString());
        snapshot.put("priceLimit", item.priceLimit() == null ? null : item.priceLimit().toPlainString());
        snapshot.put("enabled", item.enabled());
        return snapshot;
    }

    /** 参保快照（审计 old→new 用）。 */
    private static Map<String, Object> memberSnapshot(Member member) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("insuranceType", member.insuranceType());
        snapshot.put("region", member.region());
        snapshot.put("personCategory", member.personCategory());
        snapshot.put("deductible", member.deductible() == null ? null : member.deductible().toPlainString());
        snapshot.put("reimbursementRatio", member.reimbursementRatio() == null
                ? null : member.reimbursementRatio().toPlainString());
        snapshot.put("cap", member.cap() == null ? null : member.cap().toPlainString());
        snapshot.put("effectiveFrom", member.effectiveFrom() == null ? null : member.effectiveFrom().toString());
        snapshot.put("effectiveTo", member.effectiveTo() == null ? null : member.effectiveTo().toString());
        snapshot.put("status", member.status());
        return snapshot;
    }

    public Member member(UUID patientId) {
        return repo.findMemberByPatient(patientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_MEMBER_NOT_FOUND,
                        "该患者尚未登记参保信息"));
    }

    // ---------------------------------------------------------------- 结算清单

    /**
     * 生成/重算结算清单。
     *
     * <p>同一就诊单只有一张清单：DRAFT 可重算（版本号 CAS），CONFIRMED 拒绝重算。
     *
     * <p>参保有效期按**就诊业务日期**（门诊挂号日 / 住院出院日）校验，而不是请求时刻的"今天"：
     * 跨年补结算、月末补录时才不会把当时有效的参保判成失效。封顶线按**年度累计**扣减
     * （已确认清单的统筹合计），单次测算只是年度额度里的一段。
     */
    @Transactional
    public BillView generate(UUID visitId, String actor, com.medassist.common.security.DataScope scope) {
        ClinicalClient.ChargeSummary summary = clinicalClient.charges(visitId);
        if (summary.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.INSURANCE_NO_CHARGE);
        }
        LocalDate serviceDate = summary.serviceDate() == null
                ? BusinessTime.today() : summary.serviceDate();
        Member member = repo.findMemberByPatient(summary.patientId())
                .filter(m -> m.activeOn(serviceDate))
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_NOT_ENROLLED,
                        "该患者在就诊日期 " + serviceDate + " 无有效参保信息"));

        // 诊断快照先取（跨服务 HTTP），再锁清单行：锁内调下游会把清单行锁持到 HTTP 超时，
        // 同一就诊的重算/确认会排队（多智能体评审 D1）。
        ClinicalClient.DiagnosisSummary diagnosisSummary = clinicalClient.diagnoses(visitId);
        List<ClinicalClient.Diagnosis> diagnoses = diagnosisSummary == null
                || diagnosisSummary.diagnoses() == null
                ? List.of() : diagnosisSummary.diagnoses();

        Optional<SettlementBill> existing = repo.lockBillByVisit(visitId);
        // G64（评审 §6.2）：无清单时 guard 曾静默放行，主院区账号可为东院区就诊建单，
        // 形成"归属东院区、由无权账号发起"的错归属单据。这里用就诊院区（来自临床收费汇总）
        // 兜底判定：有清单按清单院区（权威），无清单按就诊院区；两者都取不到即拒绝（fail-closed）。
        if (!scope.allCampuses()) {
            java.util.UUID billCampus = existing.flatMap(b -> repo.findBillCampus(b.id())).orElse(null);
            java.util.UUID effectiveCampus = billCampus == null ? summary.campusId() : billCampus;
            if (effectiveCampus == null || !scope.canSeeCampus(effectiveCampus)) {
                throw new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND);
            }
        }
        // P1-D2：封顶归年按就诊业务日期，而不是"今天"（跨年生成/确认才不会被判成费用变化）
        BigDecimal capUsed = repo.insuranceUsedInYear(summary.patientId(), serviceDate,
                existing.map(SettlementBill::id).orElse(null));
        Computation c = calculate(summary.lines(), resolver(), member, capUsed);
        if (c.lines().isEmpty()) {
            // 全部收费单已退费：没有可结算的明细，不能出零元清单
            throw new BusinessException(ErrorCode.INSURANCE_NO_CHARGE,
                    "该就诊单收费已全部退费，无可结算费用");
        }
        String detail = toJson(c.lines());
        // 诊断快照：清单抬头用主诊断，全部诊断落 jsonb 供报盘/DRG 使用
        ClinicalClient.Diagnosis primaryDiagnosis = diagnoses.stream()
                .filter(ClinicalClient.Diagnosis::primary)
                .findFirst()
                .orElse(diagnoses.isEmpty() ? null : diagnoses.get(0));
        String primaryCode = primaryDiagnosis == null ? null : primaryDiagnosis.code();
        String primaryName = primaryDiagnosis == null ? null : primaryDiagnosis.name();
        String diagnosesJson = toJsonValue(diagnoses);

        SettlementBill bill;
        if (existing.isPresent()) {
            SettlementBill old = existing.get();
            if (SettlementBill.CONFIRMED.equals(old.status())) {
                throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                        "结算清单已确认，不能重算：" + old.billNo());
            }
            if (!repo.updateDraftBill(old.id(), c.total(), c.eligible(), c.deductible(),
                    c.insurance(), c.selfPay(), detail,
                    member.reimbursementRatio(), member.cap(), old.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "结算清单已被他人修改，请刷新后重试");
            }
            repo.updateBillDiagnoses(old.id(), primaryCode, primaryName, diagnoses.size(), diagnosesJson);
            bill = repo.findBillById(old.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        } else {
            SettlementBill created = new SettlementBill(UUID.randomUUID(), repo.nextBillNo(),
                    visitId, summary.patientId(), summary.patientNo(), serviceDate, member.id(),
                    c.total(), c.eligible(), c.deductible(), c.insurance(), c.selfPay(),
                    SettlementBill.DRAFT, detail, actor, null, null,
                    null, null, null, member.reimbursementRatio(), member.cap(), 0L, null,
                    primaryCode, primaryName, diagnoses.size(), diagnosesJson);
            if (!repo.insertBill(created, summary.campusId() == null
                    ? com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID
                    : summary.campusId())) {
                // 并发首插：另一请求已抢先建单（有效清单唯一索引兜底），让对方刷新后重试
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该就诊正在被其他终端生成结算清单，请刷新后重试");
            }
            repo.updateBillDiagnoses(created.id(), primaryCode, primaryName, diagnoses.size(), diagnosesJson);
            bill = repo.findBillById(created.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        }
        audit(actor, "BILL_GENERATE", "SETTLEMENT_BILL", bill.id().toString(), Map.of(
                "billNo", bill.billNo(), "visitId", visitId.toString(),
                "serviceDate", serviceDate.toString(),
                "total", c.total().toPlainString(), "insurance", c.insurance().toPlainString(),
                "selfPay", c.selfPay().toPlainString(), "lines", c.lines().size(),
                "capUsed", c.capUsed().toPlainString(),
                "capRemaining", c.capRemaining() == null ? "UNLIMITED" : c.capRemaining().toPlainString(),
                "skippedRefunded", c.skippedRefunded()));
        return new BillView(bill, c.lines(), c.capUsed(), c.capRemaining());
    }

    @Transactional
    public BillView confirm(UUID billId, String actor) {
        // 锁序（与 generate 一致）：无锁读 → 锁外取临床快照 → 加行锁 → 用快照比对。
        // 锁内调下游会把行锁持到 HTTP 超时，同一就诊的确认/重算/作废会全部排队。
        SettlementBill snapshotBill = repo.findBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.DRAFT.equals(snapshotBill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "只有草稿状态的结算清单可以确认，当前：" + snapshotBill.status());
        }
        // 锁外重算：确认前复核费用闭合（清单生成后临床仍可能继续计费或退费）
        Computation fresh = fetchFreshComputation(snapshotBill);

        SettlementBill bill = repo.lockBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.DRAFT.equals(bill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "只有草稿状态的结算清单可以确认，当前：" + bill.status());
        }
        assertChargesUnchanged(bill, fresh);
        if (!repo.confirmBill(billId, actor, bill.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算清单状态已变化，请刷新后重试");
        }
        audit(actor, "BILL_CONFIRM", "SETTLEMENT_BILL", billId.toString(), Map.of(
                "billNo", bill.billNo(), "total", bill.totalAmount().toPlainString()));
        return view(billId);
    }

    /**
     * 作废（红冲）：CONFIRMED → VOIDED，单向不可逆。
     *
     * <p>作废后该就诊可以重新生成清单（有效清单唯一索引排除 VOIDED），
     * 原单保留全部金额、确认人与作废原因，供对账与审计追溯。
     *
     * <p>已纳入报盘批次（GENERATED/ACCEPTED）的清单不得直接作废：
     * 否则报文已固化、省局台账与院内台账劈叉（第四轮 SF-01）。
     * 未回执批次先撤销批次，已回执批次须按省局冲销流程处理。
     */
    @Transactional
    public BillView voidBill(UUID billId, String reason, String actor) {
        SettlementBill bill = repo.lockBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.CONFIRMED.equals(bill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "只有已确认的结算清单可以作废，当前：" + bill.status());
        }
        repo.findActiveBatchByBill(billId).ifPresent(batch -> {
            String hint = ExportBatch.GENERATED.equals(batch.status())
                    ? "请先撤销报盘批次 " + batch.batchNo() + " 后再作废"
                    : "批次 " + batch.batchNo() + " 已登记回执（" + batch.status()
                            + "），须按省局冲销流程处理后再作废";
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_INVALID,
                    "该清单已纳入报盘批次，" + hint);
        });
        String normalized = reason == null ? "" : reason.trim();
        if (!repo.voidBill(billId, normalized, actor, bill.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算清单状态已变化，请刷新后重试");
        }
        audit(actor, "BILL_VOID", "SETTLEMENT_BILL", billId.toString(), Map.of(
                "billNo", bill.billNo(), "reason", normalized,
                "total", bill.totalAmount().toPlainString(),
                "insurance", bill.insuranceAmount().toPlainString()));
        return view(billId);
    }

    /**
     * 按就诊对账：清单金额 vs 当前收费重算结果。
     *
     * <p>用清单生成时的待遇快照重算，避免参保参数事后调整造成假差异；
     * 历史清单无快照时退回当前参保参数，参保也查不到则直接按 0 待遇无法比对（matched=false）。
     */
    public ReconcileView reconcile(UUID visitId) {
        SettlementBill bill = repo.findBillByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND,
                        "该就诊单没有有效结算清单"));
        Optional<Member> snapshot = snapshotMember(bill);
        Computation fresh = snapshot.map(m -> recompute(bill, m)).orElse(null);
        boolean matched = fresh != null
                && fresh.total().compareTo(bill.totalAmount()) == 0
                && fresh.insurance().compareTo(bill.insuranceAmount()) == 0;
        return new ReconcileView(visitId, bill.billNo(), bill.status(), matched,
                bill.totalAmount(), fresh == null ? null : fresh.total(),
                bill.insuranceAmount(), fresh == null ? null : fresh.insurance(),
                bill.selfPayAmount(), fresh == null ? null : fresh.selfPay(),
                parse(bill.detail()).size(), fresh == null ? -1 : fresh.lines().size());
    }

    /**
     * 用指定待遇重算某张清单的当前值。
     *
     * <p>年度封顶累计必须排除清单自身：已确认清单重算时若把自己也算进已用额度，
     * 复核/对账会永远对不上。
     */
    private Computation recompute(SettlementBill bill, Member member) {
        return calculate(clinicalClient.charges(bill.visitId()).lines(), resolver(), member,
                repo.insuranceUsedInYear(bill.patientId(), bill.serviceDate(), bill.id()));
    }

    /**
     * 锁外取临床快照并重算（供 confirm 使用）。
     *
     * <p>必须在加行锁之前调用：该方法会发起跨服务 HTTP，若在锁内调用会把清单行锁持到下游超时。
     * 参保快照缺失（历史清单 + 参保已删除）时返回 null，表示无法复核、不阻断。
     */
    private Computation fetchFreshComputation(SettlementBill bill) {
        return snapshotMember(bill)
                .map(m -> recompute(bill, m))
                .orElse(null);
    }

    /** 用锁外取到的重算结果比对；fresh 为 null 表示无法复核，按历史兼容放行。 */
    private void assertChargesUnchanged(SettlementBill bill, Computation fresh) {
        if (fresh == null) {
            // 历史清单 + 参保已删除：无法复核，不阻断（但要靠对账接口人工核对）
            return;
        }
        // P1-D1：此前只比总额与统筹，纳入额/起付线/自付与明细行分布变化都能"带病确认"，
        // 报盘快照与临床实际收费不一致时省局回执才发现。这里按全口径比对。
        java.util.List<String> mismatch = new java.util.ArrayList<>();
        if (fresh.total().compareTo(bill.totalAmount()) != 0) {
            mismatch.add("总额");
        }
        if (fresh.eligible().compareTo(bill.eligibleAmount()) != 0) {
            mismatch.add("纳入额");
        }
        if (fresh.deductible().compareTo(bill.deductible()) != 0) {
            mismatch.add("起付线");
        }
        if (fresh.insurance().compareTo(bill.insuranceAmount()) != 0) {
            mismatch.add("统筹支付");
        }
        if (fresh.selfPay().compareTo(bill.selfPayAmount()) != 0) {
            mismatch.add("个人自付");
        }
        List<BillLine> stored = parse(bill.detail());
        // 历史清单明细为空（早期只存金额）时不做行级比对，避免把存量草稿全部卡死
        if (!stored.isEmpty() && !lineKeys(stored).equals(lineKeys(fresh.lines()))) {
            mismatch.add("收费明细行");
        }
        if (!mismatch.isEmpty()) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "费用或参保待遇已变化（" + String.join("、", mismatch)
                            + "；清单实收 " + bill.totalAmount().toPlainString()
                            + " / 当前 " + fresh.total().toPlainString()
                            + "，含年度封顶累计），请作废后重新生成清单");
        }
    }

    /** 明细行指纹：按项目编码 + 金额 + 类别排序，用于识别"总额不变但明细被换"的情况。 */
    private static List<String> lineKeys(List<BillLine> lines) {
        return lines.stream()
                .map(l -> l.itemCode() + "|" + l.amount().stripTrailingZeros().toPlainString()
                        + "|" + l.category())
                .sorted()
                .toList();
    }

    /** 清单生成时的待遇快照；缺快照时退回当前参保（历史数据兼容）。 */
    private Optional<Member> snapshotMember(SettlementBill bill) {
        BigDecimal ratio = bill.snapshotRatio();
        BigDecimal cap = bill.snapshotCap();
        if (ratio == null) {
            return bill.memberId() == null ? Optional.empty() : repo.findMemberById(bill.memberId());
        }
        return Optional.of(new Member(bill.memberId(), bill.patientId(), bill.patientNo(),
                "-", "-", null,
                bill.deductible() == null ? ZERO : bill.deductible(),
                ratio, cap, null, null, Member.ACTIVE, 0L));
    }

    public BillView detail(UUID billId) {
        return view(billId);
    }

    public BillView byVisit(UUID visitId) {
        SettlementBill bill = repo.findBillByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND,
                        "该就诊单尚未生成结算清单"));
        return new BillView(bill, parse(bill.detail()), null, null);
    }

    public List<SettlementBill> list(UUID patientId, String status, Integer limit) {
        return list(patientId, status, limit, java.util.Set.of());
    }

    /** 结算清单列表（G48 按院区收敛；空集合表示不限院区）。 */
    public List<SettlementBill> list(UUID patientId, String status, Integer limit,
                                     java.util.Set<UUID> campusIds) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        return repo.listBills(patientId, normalized,
                limit == null || limit <= 0 ? 50 : Math.min(limit, 200), campusCsv(campusIds));
    }

    /** 院区集合转 SQL 过滤串；空集合（跨院区角色）返回空串表示不限院区（G71 统一走 CampusFilters）。 */
    static String campusCsv(java.util.Set<UUID> campusIds) {
        return com.medassist.common.security.CampusFilters.csv(campusIds);
    }

    private BillView view(UUID billId) {
        SettlementBill bill = repo.findBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        return new BillView(bill, parse(bill.detail()), null, null);
    }

    // ---------------------------------------------------------------- 计算引擎

    /**
     * 计算结果（金额保留 2 位小数，HALF_UP）。
     *
     * @param skippedRefunded 被剔除的已退费收费行数
     * @param capUsed         本年度已用统筹（同参保人已确认清单合计）
     * @param capRemaining    本次测算前的剩余封顶额度；为空表示未配置封顶（不限额）
     */
    record Computation(BigDecimal total, BigDecimal eligible, BigDecimal deductible,
                       BigDecimal insurance, BigDecimal selfPay, int skippedRefunded,
                       BigDecimal capUsed, BigDecimal capRemaining, List<BillLine> lines) {
    }

    /** 按收费项目聚合的净额行：退药红冲（负向）与原收费行在同一项目内相抵。 */
    private static final class NetLine {
        private final ClinicalClient.ChargeLine sample;
        private final String status;
        private BigDecimal amount = ZERO;

        private NetLine(ClinicalClient.ChargeLine sample, String status) {
            this.sample = sample;
            this.status = status;
        }
    }

    private static final String STATUS_PAID = "PAID";
    private static final String STATUS_UNPAID = "UNPAID";
    private static final String STATUS_REFUNDED = "REFUNDED";

    /**
     * 本地测算：目录支付类别 + 参保待遇 + 支付限价 + 年度封顶累计。
     *
     * <p>口径（有代码即口径，不允许"带出来但不用"）：
     * <ol>
     *   <li>收费单状态：REFUNDED 整单剔除（不进入清单）；非 PAID/UNPAID 一律 fail-closed；</li>
     *   <li>**按收费项目汇总净额**：退药红冲行（负向）与原收费行同项目相抵后再申报，
     *       与病案首页 {@code sumChargeByCategory} 的净额口径一致（第六轮 MF-04）；</li>
     *   <li>净额为负 = 只有红冲没有原行（数据异常），fail-closed；净额为 0 = 全额退回，不申报；</li>
     *   <li>甲类全额纳入；乙类先自付 selfPayRatio；丙类/未映射/目录停用全额自费；</li>
     *   <li>乙类等有支付限价的项目：限价内部分参与纳入计算，超限部分全额自付（overLimitAmount）；</li>
     *   <li>纳入金额扣除起付线后按报销比例计算统筹支付，并以**年度剩余封顶额度**与总额双重截断。</li>
     * </ol>
     */
    static Computation calculate(List<ClinicalClient.ChargeLine> lines,
                                 Function<String, InsuranceItem> resolver,
                                 Member member) {
        return calculate(lines, resolver, member, ZERO);
    }

    /**
     * 带年度累计额度的测算。
     *
     * @param capUsed 本年度已用统筹；封顶线按"已用 + 本次"判断，超出剩额度部分转个人自付
     */
    static Computation calculate(List<ClinicalClient.ChargeLine> lines,
                                 Function<String, InsuranceItem> resolver,
                                 Member member, BigDecimal capUsed) {
        List<BillLine> detail = new ArrayList<>(lines.size());
        BigDecimal total = ZERO;
        BigDecimal eligible = ZERO;
        int skippedRefunded = 0;

        // 1) 先按收费项目合并净额（红冲行与原行相抵），同时做状态校验
        Map<String, NetLine> net = new LinkedHashMap<>();
        for (ClinicalClient.ChargeLine line : lines) {
            String rawStatus = line.chargeStatus() == null ? "" : line.chargeStatus().trim().toUpperCase();
            if (STATUS_REFUNDED.equals(rawStatus)) {
                skippedRefunded++;
                continue;
            }
            if (!STATUS_PAID.equals(rawStatus) && !STATUS_UNPAID.equals(rawStatus)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "收费单状态不可用于医保结算：" + line.chargeNo() + "[" + line.chargeStatus() + "]");
            }
            NetLine acc = net.get(line.itemCode());
            if (acc == null) {
                acc = new NetLine(line, rawStatus);
                net.put(line.itemCode(), acc);
            }
            acc.amount = acc.amount.add(money(line.amount()));
        }

        // 2) 按净额逐项计算
        for (NetLine acc : net.values()) {
            String itemCode = acc.sample.itemCode();
            BigDecimal amount = money(acc.amount);
            if (amount.signum() < 0) {
                // 孤立负数：只有退药红冲、没有对应的原收费行，属于数据异常，不能猜口径
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "收费项目 " + itemCode + " 的退费金额超过原收费金额（净额 "
                                + amount.toPlainString() + "），请先核对红冲记录");
            }
            if (amount.signum() == 0) {
                // 全额退回：该项目无需申报，但仍计入"红冲"语义（不产生明细行）
                continue;
            }

            InsuranceItem item = resolver.apply(itemCode);
            String payCategory = item == null ? InsuranceItem.PAY_C : item.payCategory();
            BigDecimal selfPayRatio = switch (payCategory) {
                case InsuranceItem.PAY_A -> BigDecimal.ZERO;
                case InsuranceItem.PAY_B -> item.selfPayRatio() == null
                        ? BigDecimal.ZERO : item.selfPayRatio();
                default -> BigDecimal.ONE;
            };
            // 支付限价：超出部分全额自费，只有限价内金额参与纳入计算
            BigDecimal billable = amount;
            BigDecimal overLimit = ZERO;
            if (item != null && item.priceLimit() != null
                    && amount.compareTo(item.priceLimit()) > 0) {
                billable = money(item.priceLimit());
                overLimit = money(amount.subtract(billable));
            }
            BigDecimal lineEligible = billable.multiply(BigDecimal.ONE.subtract(selfPayRatio))
                    .setScale(2, RoundingMode.HALF_UP);
            total = total.add(amount);
            eligible = eligible.add(lineEligible);
            detail.add(new BillLine(acc.sample.chargeNo(), itemCode, acc.sample.itemName(),
                    acc.sample.category(), acc.status, amount,
                    item == null ? null : item.insuranceCode(), payCategory, selfPayRatio,
                    overLimit, lineEligible, item == null));
        }
        total = money(total);
        eligible = money(eligible);
        BigDecimal deductible = money(member.deductible() == null ? ZERO : member.deductible());
        BigDecimal base = eligible.subtract(deductible).max(ZERO);
        BigDecimal ratio = member.reimbursementRatio() == null
                ? BigDecimal.ZERO : member.reimbursementRatio();
        BigDecimal insurance = base.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cap = money(member.cap() == null ? ZERO : member.cap());
        BigDecimal used = money(capUsed == null ? ZERO : capUsed);
        // 封顶为 0 或负数视为未配置（不限额）；配置后按"年度剩余额度"截断
        BigDecimal remaining = cap.signum() > 0 ? cap.subtract(used).max(ZERO) : null;
        if (remaining != null && insurance.compareTo(remaining) > 0) {
            insurance = remaining;
        }
        if (insurance.compareTo(total) > 0) {
            insurance = total;
        }
        BigDecimal selfPay = money(total.subtract(insurance));
        return new Computation(total, eligible, deductible, insurance, selfPay, skippedRefunded,
                used, remaining, detail);
    }

    private Function<String, InsuranceItem> resolver() {
        // 同一次生成里同一收费项目只查一次
        Map<String, InsuranceItem> cache = new LinkedHashMap<>();
        Map<String, Boolean> resolved = new LinkedHashMap<>();
        return code -> {
            if (resolved.containsKey(code)) {
                return cache.get(code);
            }
            InsuranceItem item = repo.findMapping(code)
                    .filter(ChargeMapping::enabled)
                    .flatMap(m -> repo.findItemByCode(m.insuranceCode()))
                    .filter(InsuranceItem::enabled)
                    .orElse(null);
            cache.put(code, item);
            resolved.put(code, Boolean.TRUE);
            return item;
        };
    }

    private InsuranceItem toItem(ItemReq req) {
        // 乙类必须先自付一定比例；缺省按 0 会全额纳入，统筹支付虚高（第四轮 MF-03）
        if ("B".equals(req.payCategory()) && req.selfPayRatio() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "乙类（B）目录项必须提供自付比例（0~1）：" + req.insuranceCode().trim());
        }
        return new InsuranceItem(UUID.randomUUID(), req.insuranceCode().trim(), req.name().trim(),
                req.category(), req.payCategory(),
                req.selfPayRatio() == null ? BigDecimal.ZERO : req.selfPayRatio(),
                req.priceLimit() == null ? null : money(req.priceLimit()),
                req.enabled() == null || req.enabled(), 0L);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String toJsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "诊断快照无法序列化");
        }
    }

    private String toJson(List<BillLine> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结算明细无法序列化");
        }
    }

    private List<BillLine> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            BillLine[] arr = objectMapper.readValue(json, BillLine[].class);
            return List.of(arr);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
