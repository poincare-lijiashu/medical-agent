package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Slot;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.RegisterReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂号域规则：号源、重复挂号、状态机、退号释放。
 */
class RegistrationServiceTest {

    private ClinicalRepository repo;
    private PatientClient patientClient;
    private RegistrationService service;

    private final UUID deptId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final Dept dept = new Dept(deptId, "DEPT-IM", "内科门诊", "OUTPATIENT", true);
    private final ChargeItemDef fee =
            new ChargeItemDef(UUID.randomUUID(), "REG-GENERAL", "普通门诊诊查费", "CONSULTATION",
                    "次", new BigDecimal("10.00"), true);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        patientClient = mock(PatientClient.class);
        service = new RegistrationService(repo, patientClient, JsonMapper.builder().build());
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept));
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P20261031000001", "张三"));
    }

    @Test
    @DisplayName("正常挂号：生成就诊 + 收费单（诊查费 10.00）")
    void registerCreatesVisitAndCharge() {
        LocalDate today = LocalDate.now();
        when(repo.findActiveVisit(patientId, deptId, today)).thenReturn(Optional.empty());
        when(repo.nextVisitNo()).thenReturn("V20261031000001");
        when(repo.nextChargeNo()).thenReturn("C20261031000001");
        when(repo.findChargeItem("REG-GENERAL")).thenReturn(Optional.of(fee));

        Visit visit = service.register(
                new RegisterReq(patientId, deptId, "doctor01", null, "OUTPATIENT", null), "clerk01");

        assertThat(visit.status()).isEqualTo(Visit.REGISTERED);
        assertThat(visit.patientNo()).isEqualTo("P20261031000001");
        assertThat(visit.visitDate()).isEqualTo(today);
        verify(repo).insertVisit(any(Visit.class));

        ArgumentCaptor<Charge> charge = ArgumentCaptor.forClass(Charge.class);
        verify(repo).insertCharge(charge.capture());
        assertThat(charge.getValue().totalAmount()).isEqualByComparingTo("10.00");
        assertThat(charge.getValue().status()).isEqualTo(Charge.UNPAID);
        verify(repo).insertChargeItemWithoutOrder(any(), eq(charge.getValue().id()),
                eq("REG-GENERAL"), eq("普通门诊诊查费"), eq("CONSULTATION"), eq(new BigDecimal("10.00")));
    }

    @Test
    @DisplayName("号源已满：拒绝挂号，不写任何业务数据")
    void fullSlotRejected() {
        UUID slotId = UUID.randomUUID();
        Slot full = new Slot(slotId, deptId, "doctor01", LocalDate.now(), "AM", 5, 5, 0L);
        when(repo.lockSlot(slotId)).thenReturn(Optional.of(full));

        assertThatThrownBy(() -> service.register(
                new RegisterReq(patientId, deptId, "doctor01", slotId, "OUTPATIENT", null), "clerk01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REGISTRATION_NO_SLOT);

        verify(repo, never()).insertVisit(any());
        verify(repo, never()).occupySlot(any(), anyLong());
    }

    @Test
    @DisplayName("占号 CAS 失败（最后一个号被并发抢走）：拒绝挂号且不建就诊/收费单（P1-5）")
    void occupySlotCasFailureAbortsRegistration() {
        UUID slotId = UUID.randomUUID();
        // 加锁时还有 1 个余号（booked=4/total=5），但 UPDATE ... WHERE booked < total 时已被抢空
        Slot available = new Slot(slotId, deptId, "doctor01", LocalDate.now(), "AM", 5, 4, 0L);
        when(repo.lockSlot(slotId)).thenReturn(Optional.of(available));
        when(repo.findActiveVisit(patientId, deptId, LocalDate.now())).thenReturn(Optional.empty());
        when(repo.occupySlot(slotId, 0L)).thenReturn(false);

        assertThatThrownBy(() -> service.register(
                new RegisterReq(patientId, deptId, "doctor01", slotId, "OUTPATIENT", null), "clerk01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REGISTRATION_NO_SLOT);

        verify(repo).occupySlot(slotId, 0L);
        // 关键：CAS 失败后绝不能"号没占上却建了就诊和收费单"
        verify(repo, never()).insertVisit(any());
        verify(repo, never()).insertCharge(any());
        verify(repo, never()).insertChargeItemWithoutOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("同日同科室重复挂号：拒绝并给出已有就诊号")
    void duplicateVisitRejected() {
        LocalDate today = LocalDate.now();
        Visit existing = new Visit(UUID.randomUUID(), "V20261031000009", patientId, "P1",
                deptId, "doctor01", null, "OUTPATIENT", Visit.REGISTERED, today, 0L);
        when(repo.findActiveVisit(patientId, deptId, today)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.register(
                new RegisterReq(patientId, deptId, "doctor01", null, "OUTPATIENT", null), "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("V20261031000009")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REGISTRATION_DUPLICATED);
    }

    @Test
    @DisplayName("退号：释放号源")
    void cancelReleasesSlot() {
        UUID visitId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        Visit visit = new Visit(visitId, "V1", patientId, "P1", deptId, "doctor01", slotId,
                "OUTPATIENT", Visit.REGISTERED, LocalDate.now(), 3L);
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit));
        when(repo.listOrders(visitId, null)).thenReturn(List.of());
        when(repo.listCharges(visitId)).thenReturn(List.of(
                new Charge(UUID.randomUUID(), "C1", visitId, new BigDecimal("10.00"),
                        Charge.UNPAID, null, null, 0L)));
        when(repo.updateVisitStatus(visitId, Visit.REGISTERED, Visit.CANCELLED, 3L)).thenReturn(true);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit));

        service.cancel(visitId, "clerk01");

        verify(repo).releaseSlot(slotId);
    }

    @Test
    @DisplayName("已开医嘱的就诊不能直接退号（必须走退费/作废流程）")
    void cancelRejectedWhenOrdersExist() {
        UUID visitId = UUID.randomUUID();
        Visit visit = new Visit(visitId, "V1", patientId, "P1", deptId, "doctor01", null,
                "OUTPATIENT", Visit.REGISTERED, LocalDate.now(), 0L);
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit));
        when(repo.listOrders(visitId, null)).thenReturn(List.of(
                new VisitOrder(UUID.randomUUID(), "O1", visitId, "EXAM", "EXM-CBC", "血常规",
                        null, null, 1, 1, "次", new BigDecimal("25.00"), new BigDecimal("25.00"),
                        VisitOrder.PLACED, "doctor01", 0L, VisitOrder.TEMP, LocalDate.now(), null,
                        VisitOrder.TRANSFER_NONE, null, null, null, null)));

        assertThatThrownBy(() -> service.cancel(visitId, "clerk01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VISIT_STATUS_INVALID);
        verify(repo, never()).updateVisitStatus(any(), any(), any(), any(Long.class));
    }
}
