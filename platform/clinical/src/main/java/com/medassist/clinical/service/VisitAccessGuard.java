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
}
