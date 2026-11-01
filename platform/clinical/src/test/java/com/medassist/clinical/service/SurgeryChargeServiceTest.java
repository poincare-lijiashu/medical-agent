package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryChargeRule;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.SurgeryChargeDtos.ChargeLineView;
import com.medassist.clinical.web.SurgeryChargeDtos.SurgeryChargePreview;
import com.medassist.clinical.web.SurgeryChargeDtos.SurgeryChargeResult;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 术中收费打包规则：按术式类别/麻醉方式/耗材展开、价格只取物价字典、幂等计费、完成后才可计。
 */
class SurgeryChargeServiceTest {

    private ClinicalRepository repo;
    private SurgeryChargeService service;

    private final UUID caseId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID chargeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new SurgeryChargeService(repo);
    }

    // ---------------------------------------------------------------- 夹具

    private SurgeryCase surgery(String status, String anesthesiaType) {
        return new SurgeryCase(caseId, "OP20261101000001", visitId, null, UUID.randomUUID(),
                "47.0901", "阑尾切除术", Instant.now(), Instant.now(), Instant.now(),
                "doctor01", null, anesthesiaType, "II", "II", status, null, "doctor01", 2L,
                Instant.now());
    }

    private Visit visit(String status) {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", UUID.randomUUID(), "doctor01",
                null, "INPATIENT", status, LocalDate.now(), 0L);
    }

    private SurgeryChargeRule rule(String type, String match, String itemCode, String quantity) {
        return new SurgeryChargeRule(UUID.randomUUID(), type, match, itemCode,
                new BigDecimal(quantity), 1, true, 0L);
    }

    private ChargeItemDef item(String code, String name, String category, String price,
                               boolean enabled) {
        return new ChargeItemDef(UUID.randomUUID(), code, name, category, "次",
                new BigDecimal(price), enabled);
    }

    private Charge openCharge(String total) {
        return new Charge(chargeId, "CH-001", visitId, new BigDecimal(total), Charge.UNPAID,
                null, null, 0L);
    }

    /** 普外 + 全身麻醉的标准规则集。 */
    private void stubStandardRules() {
        when(repo.findOperation("47.0901")).thenReturn(Optional.of(
                new OperationCatalog("47.0901", "阑尾切除术", "普外科", true, 0L)));
        when(repo.listSurgeryChargeRules(SurgeryChargeRule.OPERATION_CATEGORY, "普外科"))
                .thenReturn(List.of(rule(SurgeryChargeRule.OPERATION_CATEGORY, "普外科",
                        "SURG-GENERAL", "1")));
        when(repo.listSurgeryChargeRules(SurgeryChargeRule.ANESTHESIA_METHOD, "GENERAL"))
                .thenReturn(List.of(rule(SurgeryChargeRule.ANESTHESIA_METHOD, "GENERAL",
                        "ANES-GENERAL", "1")));
        when(repo.listSurgeryChargeRules(SurgeryChargeRule.MATERIAL, null))
                .thenReturn(List.of(rule(SurgeryChargeRule.MATERIAL, "ALL", "MAT-OPKIT", "1")));
        when(repo.findChargeItem("SURG-GENERAL")).thenReturn(Optional.of(
                item("SURG-GENERAL", "手术费（普外）", "TREATMENT", "800.00", true)));
        when(repo.findChargeItem("ANES-GENERAL")).thenReturn(Optional.of(
                item("ANES-GENERAL", "麻醉费（全身麻醉）", "TREATMENT", "600.00", true)));
        when(repo.findChargeItem("MAT-OPKIT")).thenReturn(Optional.of(
                item("MAT-OPKIT", "一次性手术耗材包", "MATERIAL", "150.00", true)));
    }

    // ---------------------------------------------------------------- 预览

    @Test
    @DisplayName("预览：普外 + 全麻 = 手术费 800 + 麻醉费 600 + 耗材 150，合计 1550")
    void previewResolvesPackage() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();

        SurgeryChargePreview preview = service.preview(caseId);

        assertThat(preview.lines()).hasSize(3);
        assertThat(preview.lines()).extracting(ChargeLineView::itemCode)
                .containsExactly("SURG-GENERAL", "ANES-GENERAL", "MAT-OPKIT");
        assertThat(preview.totalAmount()).isEqualByComparingTo("1550.00");
        assertThat(preview.pendingAmount()).isEqualByComparingTo("1550.00");
        assertThat(preview.fullyCharged()).isFalse();
        assertThat(preview.lines()).allMatch(l -> !l.charged());
    }

    @Test
    @DisplayName("预览：已计费项标记 charged，待计金额只算未计部分")
    void previewMarksChargedItems() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();
        when(repo.listChargedSurgeryItemCodes(caseId)).thenReturn(List.of("SURG-GENERAL"));

        SurgeryChargePreview preview = service.preview(caseId);

        assertThat(preview.lines()).filteredOn(ChargeLineView::charged)
                .extracting(ChargeLineView::itemCode).containsExactly("SURG-GENERAL");
        assertThat(preview.pendingAmount()).isEqualByComparingTo("750.00");
        assertThat(preview.totalAmount()).isEqualByComparingTo("1550.00");
        assertThat(preview.fullyCharged()).isFalse();
    }

    @Test
    @DisplayName("预览：全部已计费 → fullyCharged=true、待计 0")
    void previewFullyCharged() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();
        when(repo.listChargedSurgeryItemCodes(caseId))
                .thenReturn(List.of("SURG-GENERAL", "ANES-GENERAL", "MAT-OPKIT"));

        SurgeryChargePreview preview = service.preview(caseId);

        assertThat(preview.fullyCharged()).isTrue();
        assertThat(preview.pendingAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("预览：术式类别无规则 → 用 DEFAULT 兜底")
    void previewFallsBackToDefaultRule() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "NONE")));
        when(repo.findOperation("47.0901")).thenReturn(Optional.of(
                new OperationCatalog("47.0901", "阑尾切除术", "未知科室", true, 0L)));
        when(repo.listSurgeryChargeRules(SurgeryChargeRule.OPERATION_CATEGORY, "未知科室"))
                .thenReturn(List.of());
        when(repo.listSurgeryChargeRules(SurgeryChargeRule.OPERATION_CATEGORY,
                SurgeryChargeRule.DEFAULT_MATCH))
                .thenReturn(List.of(rule(SurgeryChargeRule.OPERATION_CATEGORY, "DEFAULT",
                        "SURG-DEFAULT", "1")));
        when(repo.findChargeItem("SURG-DEFAULT")).thenReturn(Optional.of(
                item("SURG-DEFAULT", "手术费（其他）", "TREATMENT", "500.00", true)));

        SurgeryChargePreview preview = service.preview(caseId);

        assertThat(preview.lines()).extracting(ChargeLineView::itemCode)
                .containsExactly("SURG-DEFAULT");
        assertThat(preview.totalAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("预览：麻醉方式 NONE → 不计麻醉费（耗材仍计）")
    void previewSkipsAnesthesiaWhenNone() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "NONE")));
        stubStandardRules();

        SurgeryChargePreview preview = service.preview(caseId);

        assertThat(preview.lines()).extracting(ChargeLineView::itemCode)
                .containsExactly("SURG-GENERAL", "MAT-OPKIT");
    }

    @Test
    @DisplayName("预览：规则引用不存在的收费项目 → 422 并指出编码")
    void previewRejectsMissingChargeItem() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();
        when(repo.findChargeItem("MAT-OPKIT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("MAT-OPKIT");
                });
    }

    @Test
    @DisplayName("预览：收费项目已停用 → 422")
    void previewRejectsDisabledChargeItem() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();
        when(repo.findChargeItem("ANES-GENERAL")).thenReturn(Optional.of(
                item("ANES-GENERAL", "麻醉费（全身麻醉）", "TREATMENT", "600.00", false)));

        assertThatThrownBy(() -> service.preview(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ---------------------------------------------------------------- 计费

    @Test
    @DisplayName("计费：未完成手术 → 422，不写任何费用")
    void chargeRequiresCompletedSurgery() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS, "GENERAL")));

        assertThatThrownBy(() -> service.charge(caseId, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertChargeItem(any(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("计费：就诊已取消 → 422")
    void chargeRejectsCancelledVisit() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.CANCELLED)));

        assertThatThrownBy(() -> service.charge(caseId, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_STATUS_INVALID));
    }

    @Test
    @DisplayName("计费：三条费用入账、累加 1550 并留审计")
    void chargeWritesPackage() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(openCharge("0.00")));
        when(repo.insertChargeItem(any(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(true);
        when(repo.addChargeAmount(eq(chargeId), any(), eq(0L))).thenReturn(true);
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(openCharge("1550.00")));

        SurgeryChargeResult result = service.charge(caseId, "nurse01");

        assertThat(result.addedAmount()).isEqualByComparingTo("1550.00");
        assertThat(result.chargeTotal()).isEqualByComparingTo("1550.00");
        assertThat(result.lines()).hasSize(3);
        verify(repo).addChargeAmount(eq(chargeId), any(), eq(0L));
        verify(repo).logChange(eq("nurse01"), eq("SURGERY_CHARGE"), eq("SURGERY_CASE"),
                eq(caseId.toString()), anyString());
    }

    @Test
    @DisplayName("计费：已全部计费 → addedAmount=0，不续开收费单、不加金额")
    void chargeIsIdempotent() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.listChargedSurgeryItemCodes(caseId))
                .thenReturn(List.of("SURG-GENERAL", "ANES-GENERAL", "MAT-OPKIT"));
        when(repo.findLatestCharge(visitId)).thenReturn(Optional.of(openCharge("1550.00")));

        SurgeryChargeResult result = service.charge(caseId, "nurse01");

        assertThat(result.addedAmount()).isEqualByComparingTo("0");
        assertThat(result.chargeNo()).isEqualTo("CH-001");
        assertThat(result.lines()).isEmpty();
        verify(repo, never()).insertCharge(any());
        verify(repo, never()).addChargeAmount(any(), any(), anyLong());
        verify(repo).logChange(eq("nurse01"), eq("SURGERY_CHARGE_IDEMPOTENT"),
                eq("SURGERY_CASE"), eq(caseId.toString()), anyString());
    }

    @Test
    @DisplayName("计费：无未结收费单 → 续开一张（中途结算后继续住院）")
    void chargeCreatesChargeSheetWhenMissing() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.empty());
        when(repo.nextChargeNo()).thenReturn("CH-002");
        when(repo.insertChargeItem(any(), any(), isNull(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(true);
        when(repo.addChargeAmount(any(), any(), eq(0L))).thenReturn(true);
        when(repo.findCharge(any())).thenReturn(Optional.of(
                new Charge(chargeId, "CH-002", visitId, new BigDecimal("1550.00"),
                        Charge.UNPAID, null, null, 1L)));

        SurgeryChargeResult result = service.charge(caseId, "nurse01");

        verify(repo).insertCharge(any());
        assertThat(result.chargeNo()).isEqualTo("CH-002");
        assertThat(result.addedAmount()).isEqualByComparingTo("1550.00");
    }

    @Test
    @DisplayName("计费：收费单版本冲突 → 409（不能返回已成功）")
    void chargeCasConflict() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "GENERAL")));
        stubStandardRules();
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(openCharge("0.00")));
        when(repo.insertChargeItem(any(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(true);
        when(repo.addChargeAmount(eq(chargeId), any(), eq(0L))).thenReturn(false);

        assertThatThrownBy(() -> service.charge(caseId, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("计费：规则全部缺失 → 422（不静默计 0 元）")
    void chargeRejectsEmptyPackage() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "NONE")));
        when(repo.findOperation("47.0901")).thenReturn(Optional.empty());
        when(repo.listSurgeryChargeRules(anyString(), any())).thenReturn(List.of());
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));

        assertThatThrownBy(() -> service.charge(caseId, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("预览：数量 2 的耗材按 单价×2 计算")
    void previewMultipliesQuantity() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED, "NONE")));
        when(repo.findOperation("47.0901")).thenReturn(Optional.empty());
        when(repo.listSurgeryChargeRules(SurgeryChargeRule.OPERATION_CATEGORY,
                SurgeryChargeRule.DEFAULT_MATCH)).thenReturn(List.of(
                rule(SurgeryChargeRule.OPERATION_CATEGORY, "DEFAULT", "SURG-DEFAULT", "1")));
        when(repo.listSurgeryChargeRules(SurgeryChargeRule.MATERIAL, null)).thenReturn(List.of(
                rule(SurgeryChargeRule.MATERIAL, "ALL", "MAT-OPKIT", "2")));
        when(repo.findChargeItem("SURG-DEFAULT")).thenReturn(Optional.of(
                item("SURG-DEFAULT", "手术费（其他）", "TREATMENT", "500.00", true)));
        when(repo.findChargeItem("MAT-OPKIT")).thenReturn(Optional.of(
                item("MAT-OPKIT", "一次性手术耗材包", "MATERIAL", "150.00", true)));

        SurgeryChargePreview preview = service.preview(caseId);

        assertThat(preview.lines()).filteredOn(l -> "MAT-OPKIT".equals(l.itemCode()))
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.quantity()).isEqualByComparingTo("2");
                    assertThat(l.amount()).isEqualByComparingTo("300.00");
                });
        assertThat(preview.totalAmount()).isEqualByComparingTo("800.00");
    }
}
