package com.medassist.pharmacy.service;

import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionAudit;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 处方前置审方（用药安全规则）。
 *
 * <p>规则分级（见 {@link PrescriptionAudit}）：
 * <ul>
 *   <li><b>BLOCK</b>：儿童禁用、日剂量超上限 —— 开方直接拒绝；审核也不能通过（只能驳回后重开）；</li>
 *   <li><b>WARN</b>：老年慎用、特殊使用级抗菌药 —— 提示但允许通过（需要药师判断/会诊）；</li>
 *   <li><b>INFO</b>：麻醉/精神类 —— 告知发药需双人核对（该规则另有强制校验）。</li>
 * </ul>
 *
 * <p>设计取舍：
 * <ul>
 *   <li>剂量规则只在"结构化剂量"存在时生效：用法用量是自由文本，猜文本语义会误报；
 *       日剂量由数据库生成列（single_dose × times_per_day）保证唯一口径；</li>
 *   <li>规则读取的是**当前**药品字典：字典新增禁忌后，审核复评会立刻拦下在途处方；</li>
 *   <li>审方只做"事实判断"，不替药师做决定：BLOCK 才是硬拦截，WARN/INFO 记录下来供追溯。</li>
 * </ul>
 */
@Service
public class PrescriptionAuditService {

    /** 儿童上限：国内处方审核通常以 14 岁为界。 */
    private static final int CHILD_AGE = 14;
    /** 老年下限：65 岁。 */
    private static final int ELDER_AGE = 65;

    private final PharmacyRepository repo;

    public PrescriptionAuditService(PharmacyRepository repo) {
        this.repo = repo;
    }

    /** 单条规则命中。 */
    public record Finding(String level, String ruleCode, String message) {
    }

    /**
     * 评估一条处方明细。
     *
     * @param ageYears 患者年龄；未知（EMPI 无出生日期）时传 null，跳过年龄相关规则
     */
    public List<Finding> evaluate(PrescriptionItem item, Drug drug, Integer ageYears) {
        List<Finding> findings = new ArrayList<>(4);

        // R-DOSE-001：日剂量超过字典上限（只有结构化剂量才判断）
        BigDecimal max = drug.maxDailyDose();
        BigDecimal daily = item.dailyDose();
        if (max != null && daily != null && daily.compareTo(max) > 0) {
            findings.add(new Finding(PrescriptionAudit.LEVEL_BLOCK, "R-DOSE-001",
                    drug.name() + " 日剂量 " + strip(daily) + (item.doseUnit() == null ? "" : item.doseUnit())
                            + " 超过上限 " + strip(max) + "，请调整单次剂量或给药频次"));
        }

        // R-PED-001：儿童禁用
        if (ageYears != null && ageYears < CHILD_AGE && drug.pediatricContraindicated()) {
            findings.add(new Finding(PrescriptionAudit.LEVEL_BLOCK, "R-PED-001",
                    drug.name() + " 儿童（<" + CHILD_AGE + " 岁）禁用，患者 " + ageYears + " 岁"));
        }

        // R-GER-001：老年慎用
        if (ageYears != null && ageYears >= ELDER_AGE && drug.elderlyCaution()) {
            findings.add(new Finding(PrescriptionAudit.LEVEL_WARN, "R-GER-001",
                    drug.name() + " 老年人（>=" + ELDER_AGE + " 岁）慎用，请评估肝肾功能并减量"));
        }

        // R-ABX-001：特殊使用级抗菌药物
        if (drug.antibioticLevel() == 3) {
            findings.add(new Finding(PrescriptionAudit.LEVEL_WARN, "R-ABX-001",
                    drug.name() + " 为特殊使用级抗菌药物，需具备会诊意见方可使用"));
        }

        // R-NARC-001：麻醉/精神类双人核对（发药时强制校验，这里只做告知）
        if (drug.narcotic()) {
            findings.add(new Finding(PrescriptionAudit.LEVEL_INFO, "R-NARC-001",
                    drug.name() + " 为麻醉/精神类药品，发药需两名药师核对"));
        }
        return findings;
    }

    /** 批量评估（同一患者年龄）。 */
    public List<Finding> evaluate(List<PrescriptionItem> items, List<Drug> drugs, Integer ageYears) {
        List<Finding> all = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Drug drug = i < drugs.size() ? drugs.get(i) : null;
            if (drug != null) {
                all.addAll(evaluate(items.get(i), drug, ageYears));
            }
        }
        return all;
    }

    /** 落审方记录（追加式）；同一规则多条只记一次，避免刷屏。 */
    public void record(UUID prescriptionId, String phase, List<Finding> findings) {
        List<String> seen = new ArrayList<>();
        for (Finding finding : findings) {
            String key = finding.ruleCode() + "|" + finding.message();
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            repo.insertAudit(new PrescriptionAudit(UUID.randomUUID(), prescriptionId, phase,
                    finding.level(), finding.ruleCode(), finding.message(), null));
        }
    }

    public boolean hasBlock(List<Finding> findings) {
        return findings.stream().anyMatch(f -> PrescriptionAudit.LEVEL_BLOCK.equals(f.level()));
    }

    public List<PrescriptionAudit> list(UUID prescriptionId) {
        return repo.listAudits(prescriptionId);
    }

    /** 文案里的金额/剂量去掉无意义的尾零（12.500 -> 12.5）。 */
    private static String strip(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
