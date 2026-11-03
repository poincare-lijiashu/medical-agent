package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 就诊级访问校验：医嘱、收费、病案首页等所有 {@code /visits/{visitId}/...} 接口共用这一关。
 *
 * <p>用例的重点不是"能查到"，而是"查不到时必须伪装成不存在"：
 * 权限系统一旦在这里返回 403，就等于告诉调用方该就诊真实存在。
 */
class VisitAccessGuardTest {

    private ClinicalRepository repo;
    private VisitAccessGuard guard;

    private final UUID visitId = UUID.randomUUID();
    private final UUID deptA = UUID.randomUUID();
    private final UUID deptB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        guard = new VisitAccessGuard(repo);
    }

    private Visit visitOf(UUID deptId) {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", deptId, "doctor01",
                null, "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    @Test
    @DisplayName("同科室：放行并返回就诊")
    void allowsOwnDepartment() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visitOf(deptA)));

        Visit result = guard.require(DataScope.ofDepartment("doctor01", deptA, true), visitId);

        assertThat(result.id()).isEqualTo(visitId);
    }

    @Test
    @DisplayName("跨科室：404 而不是 403（403 等于确认该就诊存在）")
    void rejectsOtherDepartment() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visitOf(deptA)));

        assertThatThrownBy(() ->
                guard.require(DataScope.ofDepartment("doctor02", deptB, true), visitId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("全院角色：不受科室限制（药师审方必须能看跨科室处方）")
    void allowsAllDepartmentsRole() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visitOf(deptA)));

        Visit result = guard.require(DataScope.all("pharm01", false), visitId);

        assertThat(result.id()).isEqualTo(visitId);
    }

    @Test
    @DisplayName("就诊不存在：404，且不会继续查业务数据")
    void rejectsMissingVisit() {
        when(repo.findVisit(visitId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.require(DataScope.all("admin", true), visitId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("就诊缺科室（历史脏数据）：单科室角色一律不可见")
    void rejectsVisitWithoutDepartment() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(
                new Visit(visitId, "V1", UUID.randomUUID(), "P1", null, "doctor01",
                        null, "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L)));

        assertThatThrownBy(() ->
                guard.require(DataScope.ofDepartment("doctor01", deptA, true), visitId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 按 caseId 访问

    @Test
    @DisplayName("手术病例：同科室放行 / 跨科室 404 / 不存在 404")
    void requireCaseScopesByVisitDepartment() {
        UUID caseId = UUID.randomUUID();
        when(repo.findVisitDepartmentByCaseId(caseId)).thenReturn(Optional.of(deptA));

        guard.requireCase(DataScope.ofDepartment("doctor01", deptA, true), caseId);

        assertThatThrownBy(() ->
                guard.requireCase(DataScope.ofDepartment("doctor02", deptB, true), caseId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        UUID missing = UUID.randomUUID();
        when(repo.findVisitDepartmentByCaseId(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireCase(DataScope.all("admin", true), missing))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
