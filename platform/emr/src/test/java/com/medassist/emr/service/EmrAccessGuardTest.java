package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.repo.EmrRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 病历域行级权限：跨科室必须伪装成"不存在"，不能返回 403（否则等于确认病案存在）。
 */
class EmrAccessGuardTest {

    private EmrRepository repo;
    private EmrAccessGuard guard;

    private final UUID deptA = UUID.randomUUID();
    private final UUID deptB = UUID.randomUUID();
    private final UUID campusA = DataScope.DEFAULT_CAMPUS_ID;
    private final UUID campusB = UUID.fromString("a0000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        guard = new EmrAccessGuard(repo);
    }

    @Test
    @DisplayName("病历：同科室放行，跨科室与不存在都按未找到处理")
    void requireRecordScopesByDepartment() {
        UUID recordId = UUID.randomUUID();
        when(repo.findRecordDepartment(recordId)).thenReturn(Optional.of(deptA));
        when(repo.findRecordCampus(recordId)).thenReturn(Optional.of(campusA));

        assertThatCode(() -> guard.requireRecord(
                DataScope.ofDepartment("doctor01", deptA, true), recordId))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.requireRecord(DataScope.all("qc01", false), recordId))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> guard.requireRecord(
                DataScope.ofDepartment("doctor02", deptB, true), recordId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThatError(e, ErrorCode.EMR_RECORD_NOT_FOUND));

        // G45：科室相同但院区不同，同样按未找到处理
        assertThatThrownBy(() -> guard.requireRecord(DataScope.of("doctor03",
                Set.of(deptA), false, Set.of(campusB), false, true), recordId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThatError(e, ErrorCode.EMR_RECORD_NOT_FOUND));

        UUID missing = UUID.randomUUID();
        when(repo.findRecordDepartment(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireRecord(DataScope.all("admin01", true), missing))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThatError(e, ErrorCode.EMR_RECORD_NOT_FOUND));
    }

    @Test
    @DisplayName("病案首页：同科室放行，跨科室与不存在都按未找到处理")
    void requireHomepageScopesByDepartment() {
        UUID homepageId = UUID.randomUUID();
        when(repo.findHomepageDepartment(homepageId)).thenReturn(Optional.of(deptA));

        assertThatCode(() -> guard.requireHomepage(
                DataScope.ofDepartment("doctor01", deptA, true), homepageId))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> guard.requireHomepage(
                DataScope.ofDepartment("doctor02", deptB, true), homepageId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThatError(e, ErrorCode.EMR_HOMEPAGE_NOT_FOUND));

        UUID missing = UUID.randomUUID();
        when(repo.findHomepageDepartment(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireHomepage(DataScope.all("admin01", true), missing))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThatError(e, ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
    }

    private static void assertThatError(BusinessException e, ErrorCode expected) {
        assertThat(e.errorCode()).isEqualTo(expected);
    }
}
