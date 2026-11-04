package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.repo.TechRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 医技院区访问守卫（G49）。
 *
 * <p>申请自身的院区固化在 {@code exam_application.campus_id}；报告、危急值、影像登记
 * 都挂在申请下，因此统一回溯到申请的院区判断，避免每张子表各存一份院区后出现漂移。
 *
 * <p>越权与不存在返回同一个错误码：不能让调用方通过错误码差异探测"该单存在但你看不到"。
 * 跨院区角色（{@code allCampuses}）直接放行，由各服务继续做自身的存在性校验。
 */
@Component
public class TechAccessGuard {

    private final TechRepository repo;

    public TechAccessGuard(TechRepository repo) {
        this.repo = repo;
    }

    /** 检查/检验申请。 */
    public void requireApplication(UUID applicationId, DataScope scope) {
        require(applicationId, scope, ErrorCode.TECH_APPLICATION_NOT_FOUND);
    }

    /** 报告（回溯到所属申请）。 */
    public void requireReport(UUID reportId, DataScope scope) {
        if (scope.allCampuses()) {
            return;
        }
        Report report = repo.findReport(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_NOT_FOUND));
        require(report.applicationId(), scope, ErrorCode.TECH_REPORT_NOT_FOUND);
    }

    /** 危急值（回溯到所属申请）。 */
    public void requireCritical(UUID criticalId, DataScope scope) {
        if (scope.allCampuses()) {
            return;
        }
        require(repo.findCriticalValue(criticalId)
                        .map(c -> c.applicationId()).orElse(null),
                scope, ErrorCode.TECH_CRITICAL_NOT_FOUND);
    }

    /** 影像检查登记（回溯到所属申请）。 */
    public void requireStudy(String studyInstanceUid, DataScope scope) {
        if (scope.allCampuses()) {
            return;
        }
        require(repo.findPacsStudyByUid(studyInstanceUid)
                        .map(s -> s.applicationId()).orElse(null),
                scope, ErrorCode.TECH_PACS_STUDY_NOT_FOUND);
    }

    private void require(UUID applicationId, DataScope scope, ErrorCode notFound) {
        if (scope.allCampuses()) {
            return;
        }
        boolean visible = applicationId != null
                && repo.findApplicationCampus(applicationId).map(scope::canSeeCampus).orElse(false);
        if (!visible) {
            throw new BusinessException(notFound);
        }
    }
}
