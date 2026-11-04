package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.NursingCoverageRow;
import com.medassist.clinical.domain.ClinicalModel.NursingSchedule;
import com.medassist.clinical.domain.ClinicalModel.NursingShift;
import com.medassist.clinical.domain.ClinicalModel.NursingWorkloadRow;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.NursingScheduleDtos.AssignmentReq;
import com.medassist.clinical.web.NursingScheduleDtos.BatchScheduleReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 护理排班：批量录入去重、日期窗口、发布/取消状态机与工作量统计。
 */
class NursingScheduleServiceTest {

    private ClinicalRepository repo;
    private NursingScheduleService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID deptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new NursingScheduleService(repo, objectMapper);
    }

    private static NursingShift dayShift() {
        return new NursingShift(UUID.randomUUID(), "DAY", "白班", LocalTime.of(8, 0),
                LocalTime.of(16, 0), false, true, 0);
    }

    private static NursingSchedule schedule(String status, long version) {
        return new NursingSchedule(UUID.randomUUID(), UUID.randomUUID(), "内科病区", "DAY", "白班",
                LocalDate.of(2026, 11, 5), "张三", "N001", status, null, "admin01",
                Instant.now(), null, null, null, version);
    }

    private AssignmentReq assignment(LocalDate day) {
        return new AssignmentReq(deptId, "DAY", day, "张三", "N001", null);
    }

    @Test
    @DisplayName("批量录入：重复排班跳过并返回 created/skipped")
    void createsBatchAndSkipsDuplicates() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(
                new Dept(deptId, "D01", "内科病区", "WARD", true)));
        when(repo.findNursingShift("DAY")).thenReturn(Optional.of(dayShift()));
        when(repo.insertNursingSchedule(any(), any(), anyString(), any(), anyString(),
                any(), any(), anyString())).thenReturn(true, false);

        var result = service.create(new BatchScheduleReq(List.of(
                assignment(BusinessTime.today().plusDays(1)),
                assignment(BusinessTime.today().plusDays(1)))), "admin01");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("排班日期超出窗口 → 422")
    void rejectsOutOfWindowDate() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(
                new Dept(deptId, "D01", "内科病区", "WARD", true)));
        when(repo.findNursingShift("DAY")).thenReturn(Optional.of(dayShift()));

        assertThatThrownBy(() -> service.create(new BatchScheduleReq(List.of(
                assignment(BusinessTime.today().plusDays(91)))), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("发布：没有草稿 → 422；有草稿返回条数")
    void publishRequiresDraft() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(
                new Dept(deptId, "D01", "内科病区", "WARD", true)));
        LocalDate day = LocalDate.of(2026, 11, 5);
        when(repo.publishNursingSchedules(deptId, day)).thenReturn(0);
        assertThatThrownBy(() -> service.publish(deptId, day, "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        when(repo.publishNursingSchedules(deptId, day)).thenReturn(2);
        assertThat(service.publish(deptId, day, "admin01").published()).isEqualTo(2);
    }

    @Test
    @DisplayName("取消：已取消幂等；未填原因 422；版本冲突 409")
    void cancelStateMachine() {
        NursingSchedule cancelled = schedule(NursingSchedule.CANCELLED, 2);
        when(repo.findNursingSchedule(cancelled.id())).thenReturn(Optional.of(cancelled));
        assertThat(service.cancel(cancelled.id(), "", "admin01").status())
                .isEqualTo(NursingSchedule.CANCELLED);

        NursingSchedule draft = schedule(NursingSchedule.DRAFT, 1);
        when(repo.findNursingSchedule(draft.id())).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.cancel(draft.id(), " ", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        when(repo.cancelNursingSchedule(eq(draft.id()), anyString(), anyString(), eq(1L)))
                .thenReturn(false);
        assertThatThrownBy(() -> service.cancel(draft.id(), "临时调班", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("工作量：护士班次/夜班与每日覆盖映射")
    void mapsWorkload() {
        LocalDate from = BusinessTime.today().minusDays(6);
        LocalDate to = BusinessTime.today();
        when(repo.nursingWorkload(deptId, from, to)).thenReturn(List.of(
                new NursingWorkloadRow("张三", "N001", 5, 2)));
        when(repo.nursingCoverage(deptId, from, to)).thenReturn(List.of(
                new NursingCoverageRow(to, "DAY", 2)));

        var view = service.workload(deptId, from, to);

        assertThat(view.nurses()).hasSize(1);
        assertThat(view.nurses().get(0).shifts()).isEqualTo(5);
        assertThat(view.nurses().get(0).nightShifts()).isEqualTo(2);
        assertThat(view.coverage()).hasSize(1);
        assertThat(view.coverage().get(0).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("查询区间倒置 → 422")
    void rejectsInvalidRange() {
        LocalDate today = BusinessTime.today();
        assertThatThrownBy(() -> service.list(deptId, today, today.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, org.mockito.Mockito.never())
                .listNursingSchedules(any(), any(), any());
    }
}
