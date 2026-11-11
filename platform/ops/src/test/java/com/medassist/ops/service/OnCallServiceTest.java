package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.domain.OpsModel.OnCallRotation;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 值班轮换：成员校验、排班顺序、人工覆盖不覆盖历史。
 */
class OnCallServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 2);

    private OpsRepository repo;
    private OnCallService service;

    @BeforeEach
    void setUp() {
        repo = mock(OpsRepository.class);
        service = new OnCallService(repo);
    }

    @Test
    @DisplayName("建组：成员重复或为空直接拒绝")
    void createValidatesMembers() {
        OpsDtos.RotationReq duplicate = new OpsDtos.RotationReq("CLINICAL_ONCALL", "临床值班",
                "Asia/Shanghai", START, 7, List.of("a", "a"), LocalTime.of(8, 0));
        assertThatThrownBy(() -> service.createRotation(duplicate, "manager01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_ROTATION_INVALID);
        verify(repo, never()).insertRotation(any());
    }

    @Test
    @DisplayName("建组：组编码已存在时返回 422")
    void createDuplicateGroup() {
        when(repo.insertRotation(any())).thenReturn(false);
        OpsDtos.RotationReq req = new OpsDtos.RotationReq("CLINICAL_ONCALL", "临床值班",
                "Asia/Shanghai", START, 7, List.of("a", "b"), LocalTime.of(8, 0));

        assertThatThrownBy(() -> service.createRotation(req, "manager01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_ROTATION_INVALID);
    }

    @Test
    @DisplayName("排班：每 cycleDays 天换人，主备取相邻成员")
    void generateRotation() {
        OnCallRotation rotation = rotation();
        when(repo.findRotation(rotation.id())).thenReturn(Optional.of(rotation));
        when(repo.generateShift(any(), any(), anyString(), anyString())).thenReturn(1);
        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<String> primary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secondary = ArgumentCaptor.forClass(String.class);

        Map<String, Object> summary = service.generate(rotation.id(), START,
                START.plusDays(13), "manager01", null, null);

        assertThat(summary).containsEntry("created", 14).containsEntry("existing", 0);
        verify(repo, org.mockito.Mockito.times(14))
                .generateShift(eq(rotation.id()), date.capture(), primary.capture(),
                        secondary.capture());
        // 同一周期内主/备保持不变（7 天），第二周才轮换到 b/c
        assertThat(primary.getAllValues().subList(0, 2)).containsExactly("a", "a");
        assertThat(primary.getAllValues().subList(7, 9)).containsExactly("b", "b");
        assertThat(secondary.getAllValues().subList(0, 2)).containsExactly("b", "b");
    }

    @Test
    @DisplayName("排班：起始日不能早于轮换起始日")
    void generateBeforeStartRejected() {
        OnCallRotation rotation = rotation();
        when(repo.findRotation(rotation.id())).thenReturn(Optional.of(rotation));

        assertThatThrownBy(() -> service.generate(rotation.id(), START.minusDays(1),
                START, "manager01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_ROTATION_INVALID);
    }

    @Test
    @DisplayName("人工覆盖：写入原因并审计，不删除历史")
    void overrideShift() {
        OnCallRotation rotation = rotation();
        when(repo.findRotation(rotation.id())).thenReturn(Optional.of(rotation));

        service.overrideShift(rotation.id(), START, new OpsDtos.ShiftOverrideReq(
                "doctor99", "doctor98", "原值班人请假"), "manager01", null, null);

        verify(repo).overrideShift(rotation.id(), START, "doctor99", "doctor98", "原值班人请假");
        verify(repo).audit(eq("manager01"), eq("SHIFT_OVERRIDE"), eq("CLINICAL_ONCALL"),
                any(), any(), any());
    }

    @Test
    @DisplayName("今日值班：返回业务日期与班次列表")
    void today() {
        when(repo.shiftsOn(any(), eq("CLINICAL_ONCALL"))).thenReturn(List.of(
                Map.of("group_code", "CLINICAL_ONCALL", "primary_user", "a")));

        Map<String, Object> result = service.today("CLINICAL_ONCALL");

        assertThat(result).containsKey("date");
        assertThat((List<?>) result.get("items")).hasSize(1);
    }

    private static OnCallRotation rotation() {
        return new OnCallRotation(UUID.randomUUID(), "CLINICAL_ONCALL", "临床值班",
                "Asia/Shanghai", START, 7, List.of("a", "b", "c"), LocalTime.of(8, 0),
                "ACTIVE", 0);
    }
}
