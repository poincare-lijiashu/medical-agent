package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.DailyReversalResult;
import com.medassist.clinical.web.ClinicalDtos.NursingCorrectionReq;
import com.medassist.clinical.web.ClinicalDtos.NursingCorrectionView;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionReq;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 护理执行记录：长嘱逐日、同日唯一、状态合法、必须属于在院就诊。
 */
class NursingServiceTest {

    private ClinicalRepository repo;
    private BillingService billing;
    private NursingService service;

    private final UUID admissionId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        billing = mock(BillingService.class);
        service = new NursingService(repo, billing, JsonMapper.builder().build());
        // record 现在按 visit → admission 的顺序加锁（与按日计费一致，避免 ABBA 死锁）
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit()));
    }

    private Visit visit() {
        return new Visit(visitId, "V1", patientId, "P1", wardId, "doctor01", null,
                "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private Admission admission(String status) {
        return new Admission(admissionId, "A1", visitId, patientId, "P1", wardId,
                null, "doctor01", status, Instant.now(), null, 0L);
    }

    private VisitOrder order(String mode, String status, LocalDate start, LocalDate stop) {
        return new VisitOrder(orderId, "O1", visitId, "TREATMENT", "TRT-IV", "静脉输液",
                null, "qd", 1, 1, "次", new BigDecimal("8.00"), new BigDecimal("8.00"),
                status, "doctor01", 0L, mode, start, stop,
                VisitOrder.TRANSFER_NONE, null, null, null, null);
    }

    @Test
    @DisplayName("不允许为未来日期记录执行（与按日计费口径一致）")
    void rejectFutureDate() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));

        assertThatThrownBy(() -> service.record(admissionId,
                new NursingExecutionReq(orderId, LocalDate.now().plusDays(1), "EXECUTED", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未来日期");
        verify(repo, never()).insertNursingExecution(any());
    }

    @Test
    @DisplayName("已计费后补记 REFUSED：409（避免账实不符且无冲正路径）")
    void rejectRefusedAfterBilled() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(
                order(VisitOrder.LONG, VisitOrder.PLACED, LocalDate.now(), null)));
        when(repo.existsDailyCharge(orderId, LocalDate.now())).thenReturn(true);

        assertThatThrownBy(() -> service.record(admissionId,
                new NursingExecutionReq(orderId, null, "REFUSED", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已计费");
        verify(repo, never()).insertNursingExecution(any());
    }

    @Test
    @DisplayName("记录已执行：落库并返回医嘱号/项目名")
    void recordExecuted() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(
                order(VisitOrder.LONG, VisitOrder.PLACED, LocalDate.now(), null)));

        NursingExecutionView view = service.record(admissionId,
                new NursingExecutionReq(orderId, null, "executed", "已按医嘱输注"), "nurse01");

        assertThat(view.status()).isEqualTo(NursingExecution.EXECUTED);
        assertThat(view.orderNo()).isEqualTo("O1");
        assertThat(view.itemName()).isEqualTo("静脉输液");
        assertThat(view.execDate()).isEqualTo(LocalDate.now());
        verify(repo).insertNursingExecution(any(NursingExecution.class));
        verify(repo).logChange(eq("nurse01"), eq("NURSING_EXECUTE"), eq("ADMISSION"),
                eq(admissionId.toString()), anyString());
    }

    @Test
    @DisplayName("同日同医嘱重复记录：409，不覆盖历史")
    void recordRejectsDuplicate() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(
                order(VisitOrder.LONG, VisitOrder.PLACED, LocalDate.now(), null)));
        when(repo.findNursingExecution(orderId, LocalDate.now())).thenReturn(Optional.of(
                new NursingExecution(UUID.randomUUID(), admissionId, visitId, orderId,
                        LocalDate.now(), NursingExecution.OMITTED, "nurse02", null, Instant.now())));

        assertThatThrownBy(() -> service.record(admissionId,
                new NursingExecutionReq(orderId, null, "EXECUTED", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有执行记录");
        verify(repo, never()).insertNursingExecution(any());
    }

    @Test
    @DisplayName("并发写入撞唯一键：仍返回 409 而不是 500")
    void recordHandlesConcurrentDuplicate() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(
                order(VisitOrder.LONG, VisitOrder.PLACED, LocalDate.now(), null)));
        when(repo.findNursingExecution(orderId, LocalDate.now())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("ux_nursing_execution_order_date"))
                .when(repo).insertNursingExecution(any(NursingExecution.class));

        assertThatThrownBy(() -> service.record(admissionId,
                new NursingExecutionReq(orderId, null, "EXECUTED", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("临时医嘱不接受逐日执行记录")
    void rejectTempOrder() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(
                order(VisitOrder.TEMP, VisitOrder.PLACED, LocalDate.now(), null)));

        assertThatThrownBy(() -> service.record(admissionId,
                new NursingExecutionReq(orderId, null, "EXECUTED", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("长期医嘱");
        verify(repo, never()).insertNursingExecution(any());
    }

    @Test
    @DisplayName("已停嘱日期之后的执行记录拒绝（与计费有效期一致）")
    void rejectDateOutsideOrderWindow() {
        LocalDate today = LocalDate.now();
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(
                order(VisitOrder.LONG, VisitOrder.PLACED, today.minusDays(5), today.minusDays(1))));

        assertThatThrownBy(() -> service.record(admissionId,
                new NursingExecutionReq(orderId, today, "EXECUTED", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在有效期内");
    }

    @Test
    @DisplayName("已出院不能补记护理执行")
    void rejectInactiveAdmission() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.DISCHARGED)));

        assertThatThrownBy(() -> service.record(admissionId,
                new NursingExecutionReq(orderId, null, "EXECUTED", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已出院");
    }

    @Test
    @DisplayName("非法执行状态拒绝")
    void rejectInvalidStatus() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(
                order(VisitOrder.LONG, VisitOrder.PLACED, LocalDate.now(), null)));

        assertThatThrownBy(() -> service.record(admissionId,
                new NursingExecutionReq(orderId, null, "DONE", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("执行记录列表补齐医嘱号与项目名")
    void listEnrichesOrderInfo() {
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.listOrders(visitId, null)).thenReturn(List.of(
                order(VisitOrder.LONG, VisitOrder.PLACED, LocalDate.now(), null)));
        when(repo.listNursingExecutions(visitId, null)).thenReturn(List.of(
                new NursingExecution(UUID.randomUUID(), admissionId, visitId, orderId,
                        LocalDate.now(), NursingExecution.EXECUTED, "nurse01", null, Instant.now())));

        List<NursingExecutionView> list = service.list(admissionId, null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).orderNo()).isEqualTo("O1");
        assertThat(list.get(0).status()).isEqualTo(NursingExecution.EXECUTED);
    }

    // ---------------------------------------------------------------- 更正流程（P1-1b）

    private NursingExecution execution(String status) {
        return new NursingExecution(executionId, admissionId, visitId, orderId,
                LocalDate.now(), status, "nurse01", null, Instant.now());
    }

    private void givenCorrectionContext(String status) {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findNursingExecutionById(executionId)).thenReturn(Optional.of(execution(status)));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(
                order(VisitOrder.LONG, VisitOrder.PLACED, LocalDate.now(), null)));
    }

    @Test
    @DisplayName("更正 已执行→拒绝：联动红冲当日费用，并留存原因与操作人")
    void correctExecutedToRefusedReversesDailyCharge() {
        givenCorrectionContext(NursingExecution.EXECUTED);
        when(repo.existsDailyCharge(orderId, LocalDate.now())).thenReturn(true);
        when(billing.reverseDaily(eq(visitId), eq(orderId), eq(LocalDate.now()),
                anyString(), eq(true), eq("nurse01"))).thenReturn(new DailyReversalResult(
                orderId, LocalDate.now(), new BigDecimal("8.00"), false, "更正"));

        NursingCorrectionView view = service.correct(admissionId, executionId,
                new NursingCorrectionReq("refused", "患者外出未输注", true), "nurse01");

        assertThat(view.fromStatus()).isEqualTo(NursingExecution.EXECUTED);
        assertThat(view.toStatus()).isEqualTo(NursingExecution.REFUSED);
        assertThat(view.reversedAmount()).isEqualByComparingTo("8.00");
        assertThat(view.billingHint()).contains("已联动红冲");
        verify(repo).insertNursingCorrection(any());
        verify(repo).logChange(eq("nurse01"), eq("NURSING_CORRECTION"), eq("ADMISSION"),
                eq(admissionId.toString()), anyString());
    }

    @Test
    @DisplayName("更正 已执行→拒绝 但当日未计费：只改文书，不调用红冲")
    void correctExecutedToRefusedWithoutChargeSkipsReversal() {
        givenCorrectionContext(NursingExecution.EXECUTED);
        when(repo.existsDailyCharge(orderId, LocalDate.now())).thenReturn(false);

        NursingCorrectionView view = service.correct(admissionId, executionId,
                new NursingCorrectionReq("OMITTED", "当日未执行", true), "nurse01");

        assertThat(view.reversedAmount()).isEqualByComparingTo("0");
        assertThat(view.billingHint()).contains("未计费");
        verify(billing, never()).reverseDaily(any(), any(), any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("更正 拒绝→已执行：不自动补收，只提示到费用页重跑按日计费")
    void correctRefusedToExecutedDoesNotChargeAutomatically() {
        givenCorrectionContext(NursingExecution.REFUSED);

        NursingCorrectionView view = service.correct(admissionId, executionId,
                new NursingCorrectionReq("EXECUTED", "实际已输注", true), "nurse01");

        assertThat(view.fromStatus()).isEqualTo(NursingExecution.REFUSED);
        assertThat(view.toStatus()).isEqualTo(NursingExecution.EXECUTED);
        assertThat(view.reversedAmount()).isEqualByComparingTo("0");
        assertThat(view.billingHint()).contains("重新执行按日计费");
        verify(billing, never()).reverseDaily(any(), any(), any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("更正为同一状态：422（无变化不需要更正记录）")
    void correctRejectsSameStatus() {
        givenCorrectionContext(NursingExecution.EXECUTED);

        assertThatThrownBy(() -> service.correct(admissionId, executionId,
                new NursingCorrectionReq("EXECUTED", "没有变化", true), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertNursingCorrection(any());
    }

    @Test
    @DisplayName("已更正过的执行记录不能再改：409（每条只允许更正一次）")
    void correctRejectsSecondCorrection() {
        givenCorrectionContext(NursingExecution.EXECUTED);
        when(repo.existsNursingCorrection(executionId)).thenReturn(true);

        assertThatThrownBy(() -> service.correct(admissionId, executionId,
                new NursingCorrectionReq("REFUSED", "再次更正", true), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).contains("已更正过一次");
                });
        verify(repo, never()).insertNursingCorrection(any());
    }

    @Test
    @DisplayName("更正需要二次确认：confirmed 缺失时 422，不写任何记录")
    void correctRequiresConfirmation() {
        givenCorrectionContext(NursingExecution.EXECUTED);

        assertThatThrownBy(() -> service.correct(admissionId, executionId,
                new NursingCorrectionReq("REFUSED", "患者拒绝", null), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertNursingCorrection(any());
    }

    @Test
    @DisplayName("执行记录不属于该住院登记：404（防止跨患者改文书）")
    void correctRejectsForeignExecution() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findNursingExecutionById(executionId)).thenReturn(Optional.of(
                new NursingExecution(executionId, UUID.randomUUID(), visitId, orderId,
                        LocalDate.now(), NursingExecution.EXECUTED, "nurse01", null, Instant.now())));

        assertThatThrownBy(() -> service.correct(admissionId, executionId,
                new NursingCorrectionReq("REFUSED", "患者拒绝", true), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(repo, never()).insertNursingCorrection(any());
    }

    @Test
    @DisplayName("已出院不能更正护理执行：409")
    void correctRejectsInactiveAdmission() {
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.DISCHARGED)));

        assertThatThrownBy(() -> service.correct(admissionId, executionId,
                new NursingCorrectionReq("REFUSED", "患者拒绝", true), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).insertNursingCorrection(any());
    }
}
