package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.pharmacy.repo.PharmacyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 处方院区校验（G47）：越权与不存在同码，跨院区角色不查库。 */
class PrescriptionAccessGuardTest {

    private PharmacyRepository repo;
    private PrescriptionAccessGuard guard;

    private final UUID rxId = UUID.randomUUID();
    private final UUID campusMain = DataScope.DEFAULT_CAMPUS_ID;
    private final UUID campusEast = UUID.fromString("a0000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        guard = new PrescriptionAccessGuard(repo);
    }

    @Test
    @DisplayName("跨院区角色：直接放行且不查处方归属")
    void allowsAllCampusesWithoutLookup() {
        guard.require(DataScope.all("admin01", true), rxId);

        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("同院区：放行")
    void allowsSameCampus() {
        when(repo.findPrescriptionCampus(rxId)).thenReturn(Optional.of(campusMain));

        guard.require(DataScope.of("pharm01", Set.of(), true, Set.of(campusMain), false, true), rxId);
    }

    @Test
    @DisplayName("跨院区：404 而不是 403（不确认处方存在）")
    void rejectsOtherCampus() {
        when(repo.findPrescriptionCampus(rxId)).thenReturn(Optional.of(campusMain));

        assertThatThrownBy(() -> guard.require(
                DataScope.of("pharm02", Set.of(), true, Set.of(campusEast), false, true), rxId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.PRESCRIPTION_NOT_FOUND));
    }

    @Test
    @DisplayName("处方不存在：同样 404")
    void rejectsMissingPrescription() {
        when(repo.findPrescriptionCampus(rxId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.require(
                DataScope.of("pharm01", Set.of(), true, Set.of(campusMain), false, true), rxId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.PRESCRIPTION_NOT_FOUND));
        verify(repo).findPrescriptionCampus(rxId);
    }
}
