package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 医保结算院区校验（G48）：越权与不存在同码，跨院区角色不查库。 */
class SettlementAccessGuardTest {

    private InsuranceRepository repo;
    private SettlementAccessGuard guard;

    private final UUID billId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID campusMain = DataScope.DEFAULT_CAMPUS_ID;
    private final UUID campusEast = UUID.fromString("a0000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        repo = mock(InsuranceRepository.class);
        guard = new SettlementAccessGuard(repo);
    }

    private DataScope scopeOf(UUID campus) {
        return DataScope.of("yb01", Set.of(), true, Set.of(campus), false, true);
    }

    @Test
    @DisplayName("跨院区角色：直接放行且不查库")
    void allowsAllCampuses() {
        guard.requireBill(DataScope.all("admin01", true), billId);

        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("清单：同院区放行，跨院区 404（48403）")
    void requireBillScopesByCampus() {
        when(repo.findBillCampus(billId)).thenReturn(Optional.of(campusMain));

        assertThatCode(() -> guard.requireBill(scopeOf(campusMain), billId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireBill(scopeOf(campusEast), billId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.INSURANCE_BILL_NOT_FOUND));
    }

    @Test
    @DisplayName("按就诊：未生成清单时不拦截（交给业务层返回未生成）")
    void requireVisitPassesWhenNoBill() {
        when(repo.findBillByVisit(visitId)).thenReturn(Optional.empty());

        assertThatCode(() -> guard.requireVisit(scopeOf(campusMain), visitId)).doesNotThrowAnyException();
        verify(repo, never()).findBillCampus(billId);
    }

    @Test
    @DisplayName("按就诊：已有其他院区清单时 404（48403）")
    void requireVisitRejectsOtherCampusBill() {
        SettlementBill bill = mock(SettlementBill.class);
        when(bill.id()).thenReturn(billId);
        when(repo.findBillByVisit(visitId)).thenReturn(Optional.of(bill));
        when(repo.findBillCampus(billId)).thenReturn(Optional.of(campusMain));

        assertThatThrownBy(() -> guard.requireVisit(scopeOf(campusEast), visitId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.INSURANCE_BILL_NOT_FOUND));
    }

    @Test
    @DisplayName("实时结算交易：跨院区 404（48416）")
    void requireRealtimeScopesByBillCampus() {
        when(repo.findRealtimeBillCampus(billId)).thenReturn(Optional.of(campusMain));

        assertThatCode(() -> guard.requireRealtime(scopeOf(campusMain), billId))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireRealtime(scopeOf(campusEast), billId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.INSURANCE_REALTIME_NOT_FOUND));
    }
}
