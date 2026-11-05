package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.repo.TechRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 医技行级访问守卫（G49 院区 + G68 科室）。
 *
 * <p>申请自身的院区固化在 {@code exam_application.campus_id}、科室在 {@code dept_id}；
 * 报告、危急值、影像登记都挂在申请下，因此统一回溯到申请做 dept + campus 双条件判定，
 * 避免每张子表各存一份归属后出现漂移。
 *
 * <p>为什么收进 guard（G68，评审 §6.3）：此前 guard 只校院区，科室过滤由
 * {@code ApplicationController.detail} 在 guard 之外手工补一次——列表按科室过滤、
 * 详情却能从 {@code /tech/reports/{id}} 读到同申请的全文报告与危急值。现在两条路径同口径。
 *
 * <p>越权与不存在返回同一个错误码：不能让调用方通过错误码差异探测"该单存在但你看不到"。
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
        if (scope.allCampuses() && scope.allDepartments()) {
            return;
        }
        Report report = repo.findReport(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_NOT_FOUND));
        require(report.applicationId(), scope, ErrorCode.TECH_REPORT_NOT_FOUND);
    }

    /** 危急值（回溯到所属申请）。 */
    public void requireCritical(UUID criticalId, DataScope scope) {
        if (scope.allCampuses() && scope.allDepartments()) {
            return;
        }
        require(repo.findCriticalValue(criticalId)
                        .map(c -> c.applicationId()).orElse(null),
                scope, ErrorCode.TECH_CRITICAL_NOT_FOUND);
    }

    /** 影像检查登记（回溯到所属申请）。 */
    public void requireStudy(String studyInstanceUid, DataScope scope) {
        if (scope.allCampuses() && scope.allDepartments()) {
            return;
        }
        require(repo.findPacsStudyByUid(studyInstanceUid)
                        .map(s -> s.applicationId()).orElse(null),
                scope, ErrorCode.TECH_PACS_STUDY_NOT_FOUND);
    }

    private void require(UUID applicationId, DataScope scope, ErrorCode notFound) {
        if (scope.allCampuses() && scope.allDepartments()) {
            return;
        }
        TechRepository.ApplicationScope appScope = applicationId == null ? null
                : repo.findApplicationScope(applicationId).orElse(null);
        boolean visible = appScope != null
                && (scope.allCampuses()
                    || (appScope.campusId() != null && scope.canSeeCampus(appScope.campusId())))
                && (scope.allDepartments()
                    || (appScope.deptId() != null && scope.canSeeDepartment(appScope.deptId())));
        if (!visible) {
            throw new BusinessException(notFound);
        }
    }
}
