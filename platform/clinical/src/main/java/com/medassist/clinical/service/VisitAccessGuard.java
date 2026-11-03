package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 就诊级访问校验：所有以 {@code /visits/{visitId}/...} 为前缀的接口都必须先过这一关。
 *
 * <p>为什么做成独立组件而不是在每个 Service 里重复写：医嘱、收费、病案首页、术前访视
 * 分属不同 Service，但"这个就诊归不归你"是同一条规则。集中一处，改规则只改一个地方，
 * 也便于用一条测试覆盖所有调用方。
 *
 * <p>越权统一返回 404（{@link ErrorCode#VISIT_NOT_FOUND}）而不是 403：
 * 403 等于确认"该就诊存在，只是你没权限"，可以被用来枚举科室患者。
 */
@Component
public class VisitAccessGuard {

    private final ClinicalRepository repo;

    public VisitAccessGuard(ClinicalRepository repo) {
        this.repo = repo;
    }

    /**
     * 校验就诊在数据范围内，并返回就诊记录（避免调用方再查一次）。
     *
     * @throws BusinessException 就诊不存在，或存在但不在当前账号的科室范围内
     */
    public Visit require(DataScope scope, UUID visitId) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!scope.canSeeDepartment(visit.deptId())) {
            throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
        }
        return visit;
    }

    /**
     * 校验住院登记所属就诊在数据范围内（住院处一切按 admissionId 操作时使用）。
     *
     * @throws BusinessException 登记不存在，或存在但科室不可见
     */
    public void requireAdmission(DataScope scope, UUID admissionId) {
        UUID deptId = repo.findVisitDepartmentByAdmissionId(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到住院登记"));
        if (!scope.canSeeDepartment(deptId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到住院登记");
        }
    }

    /**
     * 校验病区在数据范围内（床位表、在院患者列表按 wardId 访问时使用）。
     *
     * <p>病区自身带 {@code dept_id}，不需要绕到就诊；病区不存在与科室不可见同样按不存在处理。
     */
    public void requireWard(DataScope scope, UUID wardId) {
        UUID deptId = repo.findWardDepartment(wardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该病区"));
        if (!scope.canSeeDepartment(deptId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该病区");
        }
    }

    /**
     * 校验手术病例所属就诊在数据范围内（术前访视、麻醉、术中护理按 caseId 访问时使用）。
     *
     * <p>用一条 JOIN 取科室，避免 case → visit 两次查询；
     * 病例不存在与科室为空都按"不存在"处理——两者对调用方是同一个结论：不可见。
     */
    public void requireCase(DataScope scope, UUID caseId) {
        UUID deptId = repo.findVisitDepartmentByCaseId(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
        if (!scope.canSeeDepartment(deptId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录");
        }
    }
}
