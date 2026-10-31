package com.medassist.pharmacy.service;

import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionAudit;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 前置审方规则：剂量上限、儿童禁用、老年慎用、特殊使用级抗菌药、麻醉药告知。
 */
class PrescriptionAuditServiceTest {

    private PharmacyRepository repo;
    private PrescriptionAuditService service;

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        service = new PrescriptionAuditService(repo);
    }

    private Drug drug(String maxDailyDose, boolean pediatric, boolean elderly,
                      int antibioticLevel, boolean narcotic) {
        return new Drug(UUID.randomUUID(), "D1", "测试药品", null, "1g", "TABLET", "盒", "片", 10,
                new BigDecimal("10.00"), null, "A", antibioticLevel, narcotic, true, 0L,
                maxDailyDose == null ? null : new BigDecimal(maxDailyDose), pediatric, elderly);
    }

    private PrescriptionItem item(BigDecimal singleDose, Integer timesPerDay) {
        BigDecimal daily = singleDose == null || timesPerDay == null ? null
                : singleDose.multiply(BigDecimal.valueOf(timesPerDay)).setScale(3);
        return new PrescriptionItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "D1", "测试药品", "1g", 1, "盒", null, null, null,
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                singleDose, singleDose == null ? null : "g", timesPerDay, daily);
    }

    @Test
    @DisplayName("日剂量超过字典上限 → BLOCK")
    void doseExceededIsBlocked() {
        List<PrescriptionAuditService.Finding> findings = service.evaluate(
                item(new BigDecimal("1.5"), 3), drug("3.0", false, false, 0, false), 40);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(PrescriptionAudit.LEVEL_BLOCK);
        assertThat(findings.get(0).ruleCode()).isEqualTo("R-DOSE-001");
        assertThat(findings.get(0).message()).contains("超过上限");
        assertThat(service.hasBlock(findings)).isTrue();
    }

    @Test
    @DisplayName("日剂量在上限内 / 无结构化剂量 → 不产生剂量问题")
    void doseWithinLimitOrMissingIsFine() {
        Drug drug = drug("3.0", false, false, 0, false);
        assertThat(service.evaluate(item(new BigDecimal("1.0"), 3), drug, 40)).isEmpty();
        // 只有自由文本用法时跳过剂量规则（不猜文本语义）
        assertThat(service.evaluate(item(null, null), drug, 40)).isEmpty();
        // 字典未维护上限时跳过
        assertThat(service.evaluate(item(new BigDecimal("9.0"), 3), drug(null, false, false, 0, false), 40))
                .isEmpty();
    }

    @Test
    @DisplayName("儿童禁用 + 患者 <14 岁 → BLOCK；成人不触发")
    void childContraindicationBlocksOnlyForChildren() {
        Drug drug = drug(null, true, false, 0, false);
        List<PrescriptionAuditService.Finding> child = service.evaluate(item(null, null), drug, 8);
        assertThat(child).anyMatch(f -> "R-PED-001".equals(f.ruleCode())
                && PrescriptionAudit.LEVEL_BLOCK.equals(f.level()));

        assertThat(service.evaluate(item(null, null), drug, 30)).isEmpty();
        // 出生日期缺失（年龄未知）时跳过，而不是误报
        assertThat(service.evaluate(item(null, null), drug, null)).isEmpty();
    }

    @Test
    @DisplayName("老年慎用 → WARN；特殊使用级抗菌药 → WARN；麻醉药 → INFO")
    void warnsForElderlySpecialAntibioticAndNarcotic() {
        List<PrescriptionAuditService.Finding> elderly = service.evaluate(
                item(null, null), drug(null, false, true, 0, false), 70);
        assertThat(elderly).anyMatch(f -> "R-GER-001".equals(f.ruleCode())
                && PrescriptionAudit.LEVEL_WARN.equals(f.level()));

        List<PrescriptionAuditService.Finding> abx = service.evaluate(
                item(null, null), drug(null, false, false, 3, false), 40);
        assertThat(abx).anyMatch(f -> "R-ABX-001".equals(f.ruleCode())
                && PrescriptionAudit.LEVEL_WARN.equals(f.level()));

        List<PrescriptionAuditService.Finding> narcotic = service.evaluate(
                item(null, null), drug(null, false, false, 0, true), 40);
        assertThat(narcotic).anyMatch(f -> "R-NARC-001".equals(f.ruleCode())
                && PrescriptionAudit.LEVEL_INFO.equals(f.level()));
        assertThat(service.hasBlock(narcotic)).isFalse();
    }

    @Test
    @DisplayName("审方记录：同一规则同文案只记一次")
    void recordDeduplicatesFindings() {
        UUID rxId = UUID.randomUUID();
        var finding = new PrescriptionAuditService.Finding(PrescriptionAudit.LEVEL_WARN,
                "R-ABX-001", "特殊使用级抗菌药物，需会诊");
        service.record(rxId, PrescriptionAudit.PHASE_CREATE, List.of(finding, finding));

        verify(repo, times(1)).insertAudit(any(PrescriptionAudit.class));
    }
}
