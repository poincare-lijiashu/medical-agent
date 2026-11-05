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
     * <p>G46 起同时校验院区：科室可见但院区不可见同样按"不存在"处理，
     * 避免跨院区账号通过就诊号确认记录存在。
     *
     * @throws BusinessException 就诊不存在，或存在但不在当前账号的科室/院区范围内
     */
    public Visit require(DataScope scope, UUID visitId) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!scope.canSeeDepartment(visit.deptId())
                || !campusOk(scope, () -> repo.findVisitCampus(visitId))) {
            throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
        }
        return visit;
    }

    /**
     * 只读校验：本科室可见，**或**受邀会诊仍在进行中（跨科室只读授权的唯一来源）。
     *
     * <p>与 {@link #require} 的差别只在"多一条会诊授权"，其余不变：
     * 会诊完成后授权立即失效，因此这里每次都要查一次状态，不做缓存。
     * 会诊授权不跨院区——院区不可见时连会诊入口也一并拒绝。
     *
     * <p>只用在读接口：受邀科室能看会诊所需的就诊/医嘱/诊断，但不能替主诊科室下医嘱、
     * 收费或改病历——那些写接口仍然走 {@link #require}。
     */
    public Visit requireRead(DataScope scope, UUID visitId) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!campusOk(scope, () -> repo.findVisitCampus(visitId))) {
            throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
        }
        if (scope.canSeeDepartment(visit.deptId())) {
            return visit;
        }
        if (repo.existsActiveConsultation(visitId, scope.deptCodes())) {
            return visit;
        }
        throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
    }

    /**
     * 校验住院登记所属就诊在数据范围内（住院处一切按 admissionId 操作时使用）。
     *
     * @throws BusinessException 登记不存在，或存在但科室/院区不可见
     */
    public void requireAdmission(DataScope scope, UUID admissionId) {
        UUID deptId = repo.findVisitDepartmentByAdmissionId(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMISSION_NOT_FOUND));
        boolean campusOk = campusOk(scope, () -> repo.findVisitCampusByAdmissionId(admissionId));
        if (!scope.canSeeDepartment(deptId) || !campusOk) {
            throw new BusinessException(ErrorCode.ADMISSION_NOT_FOUND);
        }
    }

    /**
     * 校验病区在数据范围内（床位表、在院患者列表按 wardId 访问时使用）。
     *
     * <p>病区自身带 {@code dept_id} 与 {@code campus_id}，不需要绕到就诊；
     * 病区不存在与科室/院区不可见同样按不存在处理。
     */
    public void requireWard(DataScope scope, UUID wardId) {
        UUID deptId = repo.findWardDepartment(wardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WARD_NOT_FOUND));
        boolean campusOk = campusOk(scope, () -> repo.findWardCampus(wardId));
        if (!scope.canSeeDepartment(deptId) || !campusOk) {
            throw new BusinessException(ErrorCode.WARD_NOT_FOUND);
        }
    }

    /**
     * 校验手术病例所属就诊在数据范围内（术前访视、麻醉、术中护理按 caseId 访问时使用）。
     *
     * <p>用一条 JOIN 取科室，避免 case → visit 两次查询；
     * 病例不存在与科室/院区为空都按"不存在"处理——两者对调用方是同一个结论：不可见。
     */
    public void requireCase(DataScope scope, UUID caseId) {
        UUID deptId = repo.findVisitDepartmentByCaseId(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SURGERY_CASE_NOT_FOUND));
        boolean campusOk = campusOk(scope, () -> repo.findVisitCampusByCaseId(caseId));
        if (!scope.canSeeDepartment(deptId) || !campusOk) {
            throw new BusinessException(ErrorCode.SURGERY_CASE_NOT_FOUND);
        }
    }

    /**
     * 院区可见性（G46）。
     *
     * <p>跨院区角色直接放行且不查库（药师/管理员等高频路径）；
     * 单院区角色查不到院区按不可见处理，避免脏数据退化成跨院区可见。
     */
    private boolean campusOk(DataScope scope, java.util.function.Supplier<java.util.Optional<UUID>> campus) {
        if (scope.allCampuses()) {
            return true;
        }
        return campus.get().map(scope::canSeeCampus).orElse(false);
    }
}
