package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.repo.EmrRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 病历域的行级访问校验：病案首页、病历正文都按"建档科室"判定可见性。
 *
 * <p>越权统一返回"未找到"，而不是 403：403 等于确认该病案存在，可以被用来枚举
 * 其他科室的患者与住院信息。病历域没有就诊表，科室在建档时固化在记录上，
 * 所以直接用记录自身的 {@code dept_id} 判定。
 */
@Component
public class EmrAccessGuard {

    private final EmrRepository repo;

    public EmrAccessGuard(EmrRepository repo) {
        this.repo = repo;
    }

    /** 校验病历在数据范围内（详情、打印、保存、提交、签名、归档、修订、修改申请）。 */
    public void requireRecord(DataScope scope, UUID recordId) {
        // G71：记录不存在时两个查询都为空；dept 允许为空（未挂就诊的建档），
        // 此时只有全院科室角色可读——否则"建档即无人可见"，连建单人自己都打不开。
        UUID deptId = repo.findRecordDepartment(recordId).orElse(null);
        UUID campusId = repo.findRecordCampus(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!scope.allDepartments() && (deptId == null || !scope.canSeeDepartment(deptId))) {
            throw new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND);
        }
        // G45：院区仍严格校验；缺院区的历史数据 fail-closed
        if (!scope.canSeeCampus(campusId)) {
            throw new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND);
        }
    }

    /**
     * 校验病案首页在数据范围内（详情、打印、补录、重算、提交、归档）。
     *
     * <p>G60：与 {@link #requireRecord} 同口径——dept + campus 双条件；
     * 缺损院区的历史数据按不可见处理（fail-closed），避免"同科室即可跨院区读首页"。
     */
    public void requireHomepage(DataScope scope, UUID homepageId) {
        UUID deptId = repo.findHomepageDepartment(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        UUID campusId = repo.findHomepageCampus(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!scope.canSeeDepartment(deptId) || !scope.canSeeCampus(campusId)) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND);
        }
    }
}
