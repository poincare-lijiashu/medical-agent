package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.OrderLine;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医嘱域规则：状态机、服务端计价、类别校验。
 */
class OrderServiceTest {

    private ClinicalRepository repo;
    private OrderService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new OrderService(repo, JsonMapper.builder().build());
        when(repo.nextOrderNo()).thenReturn("O20261031000001");
    }

    private Visit visit(String status) {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", deptId, "doctor01", null,
                "OUTPATIENT", status, LocalDate.now(), 0L);
    }

    @Test
    @DisplayName("未接诊（REGISTERED）不能开医嘱")
    void rejectWhenNotInTreatment() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.REGISTERED)));

        assertThatThrownBy(() -> service.place(visitId,
                List.of(new OrderLine("EXM-CBC", 1, null, null, null)), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VISIT_STATUS_INVALID);
        verify(repo, never()).insertOrder(any());
    }

    @Test
    @DisplayName("金额由主数据计算：8.00 × 3 = 24.00，类型由项目类别决定")
    void computesAmountFromMasterData() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findChargeItem("TRT-IV")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "TRT-IV", "静脉输液", "TREATMENT", "次",
                new BigDecimal("8.00"), true)));

        List<VisitOrder> orders = service.place(visitId,
                List.of(new OrderLine("TRT-IV", 3, "0.9%NS 250ml", "qd", null)), "doctor01");

        assertThat(orders).hasSize(1);
        VisitOrder o = orders.get(0);
        assertThat(o.orderType()).isEqualTo("TREATMENT");
        assertThat(o.unitPrice()).isEqualByComparingTo("8.00");
        assertThat(o.amount()).isEqualByComparingTo("24.00");
        verify(repo).insertOrder(o);
    }

    @Test
    @DisplayName("诊查费不能作为医嘱重复开立（防同一就诊收两次诊查费）")
    void rejectConsultationItemAsOrder() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findChargeItem("REG-GENERAL")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "REG-GENERAL", "普通门诊诊查费", "CONSULTATION", "次",
                new BigDecimal("10.00"), true)));

        assertThatThrownBy(() -> service.place(visitId,
                List.of(new OrderLine("REG-GENERAL", 1, null, null, null)), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("已计费医嘱不可撤销")
    void rejectCancelChargedOrder() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        UUID orderId = UUID.randomUUID();
        when(repo.cancelOrder(orderId, visitId)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(visitId, orderId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_INVALID);
    }

    @Test
    @DisplayName("门诊不能开长嘱（LONG 仅限住院）")
    void rejectLongOrderForOutpatient() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findChargeItem("TRT-IV")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "TRT-IV", "静脉输液", "TREATMENT", "次",
                new BigDecimal("8.00"), true)));

        assertThatThrownBy(() -> service.place(visitId,
                List.of(new OrderLine("TRT-IV", 1, null, "qd", "LONG")), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertOrder(any());
    }

    @Test
    @DisplayName("停嘱失败（非长嘱/已停）返回 44410")
    void stopRejectedWhenNotLongOrder() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        UUID orderId = UUID.randomUUID();
        when(repo.stopLongOrder(eq(orderId), eq(visitId), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.stop(visitId, orderId, "病情好转", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_INVALID);
    }
}
