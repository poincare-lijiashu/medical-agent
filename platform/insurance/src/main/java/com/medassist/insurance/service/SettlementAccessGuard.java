package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.insurance.repo.InsuranceRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 医保结算院区校验（G48）。
 *
 * <p>医保办是"跨科室"岗位，但**不等于跨院区**：东院区清单不能出现在主院区医保对账里。
 * 越权与不存在统一返回同码（清单 48403 / 实时结算 48416），不确认对象是否存在。
 */
@Component
public class SettlementAccessGuard {

    private final InsuranceRepository repo;

    public SettlementAccessGuard(InsuranceRepository repo) {
        this.repo = repo;
    }

    public void requireBill(DataScope scope, UUID billId) {
        if (scope.allCampuses()) {
            return;
        }
        boolean visible = repo.findBillCampus(billId).map(scope::canSeeCampus).orElse(false);
        if (!visible) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND);
        }
    }

    /** 按就诊校验：该就诊已有清单时要求院区可见；尚无清单时交给业务层返回"未生成"。 */
    public void requireVisit(DataScope scope, UUID visitId) {
        if (scope.allCampuses()) {
            return;
        }
        repo.findBillByVisit(visitId).ifPresent(bill -> {
            boolean visible = repo.findBillCampus(bill.id()).map(scope::canSeeCampus).orElse(false);
            if (!visible) {
                throw new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND);
            }
        });
    }

    public void requireRealtime(DataScope scope, UUID realtimeId) {
        if (scope.allCampuses()) {
            return;
        }
        boolean visible = repo.findRealtimeBillCampus(realtimeId).map(scope::canSeeCampus).orElse(false);
        if (!visible) {
            throw new BusinessException(ErrorCode.INSURANCE_REALTIME_NOT_FOUND);
        }
    }
}
