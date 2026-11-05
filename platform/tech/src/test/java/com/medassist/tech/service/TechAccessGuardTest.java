package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.domain.TechModel.CriticalValue;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.repo.TechRepository.ApplicationScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医技行级守卫：申请、报告、危急值、影像登记都必须收敛到申请的 dept + campus。
 *
 * <p>越权与不存在同码是刻意的：错误码一旦区分，攻击者就能枚举"哪些申请号真实存在"。
 */
class TechAccessGuardTest {

    private TechRepository repo;
    private TechAccessGuard guard;

    private final UUID campusA = UUID.randomUUID();
    private final UUID campusB = UUID.randomUUID();
    private final UUID deptA = UUID.randomUUID();
    private final UUID deptB = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    /** 只有 A 院区（全科室）：院区受限、科室不限。 */
    private final DataScope onlyCampusA = DataScope.of("tester", Set.of(deptA), true,
            Set.of(campusA), false, false);
    /** 只有 A 科室（全院区）：科室受限、院区不限。 */
    private final DataScope onlyDeptA = DataScope.of("tester", Set.of(deptA), false,
            Set.of(), true, false);

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        guard = new TechAccessGuard(repo);
    }

    private void assertNotFound(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("全院区 + 全科室：直接放行，不做额外查询")
    void allScopeSkipsLookup() {
        DataScope all = DataScope.all("admin", true);

        assertThatCode(() -> guard.requireApplication(applicationId, all))
                .doesNotThrowAnyException();
        verify(repo, never()).findApplicationScope(any());
    }

    @Test
    @DisplayName("申请：本院区放行，跨院区与不存在同为 404")
    void applicationCampusIsolation() {
        when(repo.findApplicationScope(applicationId))
                .thenReturn(Optional.of(new ApplicationScope(campusA, deptA)));
        assertThatCode(() -> guard.requireApplication(applicationId, onlyCampusA))
                .doesNotThrowAnyException();

        UUID other = UUID.randomUUID();
        when(repo.findApplicationScope(other))
                .thenReturn(Optional.of(new ApplicationScope(campusB, deptA)));
        assertNotFound(() -> guard.requireApplication(other, onlyCampusA),
                ErrorCode.TECH_APPLICATION_NOT_FOUND);

        UUID missing = UUID.randomUUID();
        when(repo.findApplicationScope(missing)).thenReturn(Optional.empty());
        assertNotFound(() -> guard.requireApplication(missing, onlyCampusA),
                ErrorCode.TECH_APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("申请：跨科室不可见（G68 修复列表可见详情可达）")
    void applicationDepartmentIsolation() {
        when(repo.findApplicationScope(applicationId))
                .thenReturn(Optional.of(new ApplicationScope(campusB, deptA)));
        assertThatCode(() -> guard.requireApplication(applicationId, onlyDeptA))
                .doesNotThrowAnyException();

        UUID other = UUID.randomUUID();
        when(repo.findApplicationScope(other))
                .thenReturn(Optional.of(new ApplicationScope(campusA, deptB)));
        assertNotFound(() -> guard.requireApplication(other, onlyDeptA),
                ErrorCode.TECH_APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("申请：科室为空的存量单对受限账号一律不可见（fail-closed）")
    void missingDepartmentIsNotVisible() {
        when(repo.findApplicationScope(applicationId))
                .thenReturn(Optional.of(new ApplicationScope(campusA, null)));

        assertNotFound(() -> guard.requireApplication(applicationId, onlyDeptA),
                ErrorCode.TECH_APPLICATION_NOT_FOUND);
        assertThatCode(() -> guard.requireApplication(applicationId, onlyCampusA))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("报告：回溯所属申请的院区，越权按报告不存在处理")
    void reportCampusIsolation() {
        UUID reportId = UUID.randomUUID();
        when(repo.findReport(reportId)).thenReturn(Optional.of(
                new Report(reportId, "RP1", applicationId, 1, true, "所见", "结论",
                        "tech01", null, null, Report.FINAL, false, Instant.now(), null, null)));
        when(repo.findApplicationScope(applicationId))
                .thenReturn(Optional.of(new ApplicationScope(campusB, deptA)));

        assertNotFound(() -> guard.requireReport(reportId, onlyCampusA),
                ErrorCode.TECH_REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("报告：跨科室同样不可读（与申请详情同口径）")
    void reportDepartmentIsolation() {
        UUID reportId = UUID.randomUUID();
        when(repo.findReport(reportId)).thenReturn(Optional.of(
                new Report(reportId, "RP1", applicationId, 1, true, "所见", "结论",
                        "tech01", null, null, Report.FINAL, false, Instant.now(), null, null)));
        when(repo.findApplicationScope(applicationId))
                .thenReturn(Optional.of(new ApplicationScope(campusA, deptB)));

        assertNotFound(() -> guard.requireReport(reportId, onlyDeptA),
                ErrorCode.TECH_REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("危急值：回溯所属申请的院区，越权按危急值不存在处理")
    void criticalCampusIsolation() {
        UUID criticalId = UUID.randomUUID();
        when(repo.findCriticalValue(criticalId)).thenReturn(Optional.of(
                new CriticalValue(criticalId, applicationId, UUID.randomUUID(), "血钾", "6.8",
                        "3.5-5.5", "HIGH", CriticalValue.PENDING, null, null, null,
                        null, null, Instant.now())));
        when(repo.findApplicationScope(applicationId))
                .thenReturn(Optional.of(new ApplicationScope(campusB, deptA)));

        assertNotFound(() -> guard.requireCritical(criticalId, onlyCampusA),
                ErrorCode.TECH_CRITICAL_NOT_FOUND);
    }

    @Test
    @DisplayName("影像登记：回溯所属申请的院区，越权按检查不存在处理")
    void studyCampusIsolation() {
        String uid = "1.2.840.113619.2.55.3.20261105.1";
        when(repo.findPacsStudyByUid(uid)).thenReturn(Optional.of(
                new PacsStudy(UUID.randomUUID(), applicationId, uid, "AC1", "CT", null,
                        PacsStudy.COMPLETED, 1, 10, null, Instant.now(), Instant.now(), 0L,
                        Instant.now())));
        when(repo.findApplicationScope(applicationId))
                .thenReturn(Optional.of(new ApplicationScope(campusB, deptA)));

        assertNotFound(() -> guard.requireStudy(uid, onlyCampusA),
                ErrorCode.TECH_PACS_STUDY_NOT_FOUND);
    }
}
