package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.domain.TechModel.CriticalValue;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.repo.TechRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医技院区守卫（G49）：申请、报告、危急值、影像登记都必须收敛到申请院区。
 *
 * <p>越权与不存在同码是刻意的：错误码一旦区分，攻击者就能枚举"哪些申请号真实存在"。
 */
class TechAccessGuardTest {

    private TechRepository repo;
    private TechAccessGuard guard;

    private final UUID campusA = UUID.randomUUID();
    private final UUID campusB = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final DataScope onlyA = DataScope.of("tester", Set.of(UUID.randomUUID()), true,
            Set.of(campusA), false, false);

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
    @DisplayName("跨院区角色：直接放行，不做额外查询")
    void allCampusesSkipsLookup() {
        DataScope all = DataScope.all("admin", true);

        assertThatCode(() -> guard.requireApplication(applicationId, all)).doesNotThrowAnyException();
        verify(repo, never()).findApplicationCampus(applicationId);
    }

    @Test
    @DisplayName("申请：本院区放行，跨院区与不存在同为 404")
    void applicationCampusIsolation() {
        when(repo.findApplicationCampus(applicationId)).thenReturn(Optional.of(campusA));
        assertThatCode(() -> guard.requireApplication(applicationId, onlyA))
                .doesNotThrowAnyException();

        UUID other = UUID.randomUUID();
        when(repo.findApplicationCampus(other)).thenReturn(Optional.of(campusB));
        assertNotFound(() -> guard.requireApplication(other, onlyA),
                ErrorCode.TECH_APPLICATION_NOT_FOUND);

        UUID missing = UUID.randomUUID();
        when(repo.findApplicationCampus(missing)).thenReturn(Optional.empty());
        assertNotFound(() -> guard.requireApplication(missing, onlyA),
                ErrorCode.TECH_APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("报告：回溯所属申请的院区，越权按报告不存在处理")
    void reportCampusIsolation() {
        UUID reportId = UUID.randomUUID();
        when(repo.findReport(reportId)).thenReturn(Optional.of(
                new Report(reportId, "RP1", applicationId, 1, true, "所见", "结论",
                        "tech01", null, null, Report.FINAL, false, Instant.now(), null, null)));
        when(repo.findApplicationCampus(applicationId)).thenReturn(Optional.of(campusB));

        assertNotFound(() -> guard.requireReport(reportId, onlyA), ErrorCode.TECH_REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("危急值：回溯所属申请的院区，越权按危急值不存在处理")
    void criticalCampusIsolation() {
        UUID criticalId = UUID.randomUUID();
        when(repo.findCriticalValue(criticalId)).thenReturn(Optional.of(
                new CriticalValue(criticalId, applicationId, UUID.randomUUID(), "血钾", "6.8",
                        "3.5-5.5", "HIGH", CriticalValue.PENDING, null, null, null, null,
                        null, Instant.now())));
        when(repo.findApplicationCampus(applicationId)).thenReturn(Optional.of(campusB));

        assertNotFound(() -> guard.requireCritical(criticalId, onlyA),
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
        when(repo.findApplicationCampus(applicationId)).thenReturn(Optional.of(campusB));

        assertNotFound(() -> guard.requireStudy(uid, onlyA), ErrorCode.TECH_PACS_STUDY_NOT_FOUND);
    }
}
