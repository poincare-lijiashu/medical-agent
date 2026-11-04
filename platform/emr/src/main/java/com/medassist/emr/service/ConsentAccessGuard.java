package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.repo.ConsentRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 知情同意书院区校验（G45）。
 *
 * <p>院区不符与不存在统一返回"未找到"，避免用 403 枚举其他院区是否存在同意书。
 */
@Component
public class ConsentAccessGuard {

    private final ConsentRepository repo;

    public ConsentAccessGuard(ConsentRepository repo) {
        this.repo = repo;
    }

    public void require(DataScope scope, UUID consentId) {
        UUID campusId = repo.findConsentCampus(consentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_NOT_FOUND));
        if (!scope.canSeeCampus(campusId)) {
            throw new BusinessException(ErrorCode.CONSENT_NOT_FOUND);
        }
    }
}
