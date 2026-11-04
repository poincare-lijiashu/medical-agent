package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.pharmacy.repo.PharmacyRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 处方级院区校验（G47）。
 *
 * <p>药房是"全院科室可见"的岗位（药师要看所有科室处方），但**不等于跨院区可见**：
 * 院区隔离必须独立生效，否则东院区处方会出现在主院区药师的待办里。
 *
 * <p>越权与不存在统一返回 {@code PRESCRIPTION_NOT_FOUND}：
 * 404 不确认处方是否存在，避免被用来枚举其他院区的处方号。
 */
@Component
public class PrescriptionAccessGuard {

    private final PharmacyRepository repo;

    public PrescriptionAccessGuard(PharmacyRepository repo) {
        this.repo = repo;
    }

    public void require(DataScope scope, UUID prescriptionId) {
        if (scope.allCampuses()) {
            return;
        }
        boolean visible = repo.findPrescriptionCampus(prescriptionId)
                .map(scope::canSeeCampus)
                .orElse(false);
        if (!visible) {
            throw new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND);
        }
    }
}
