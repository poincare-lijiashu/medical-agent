package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.material.repo.MaterialRepository;
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
import static org.mockito.Mockito.when;

/**
 * 物资单据院区守卫（G50）：出库/退回/调拨/出库明细都必须收敛到单据院区。
 *
 * <p>越权与不存在同码是刻意的：错误码一旦区分，调用方就能枚举"哪些单号真实存在"。
 */
class MaterialAccessGuardTest {

    private MaterialRepository repo;
    private MaterialAccessGuard guard;

    private final UUID campusA = UUID.randomUUID();
    private final UUID campusB = UUID.randomUUID();
    private final DataScope onlyA = DataScope.of("tester", Set.of(UUID.randomUUID()), true,
            Set.of(campusA), false, false);

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        guard = new MaterialAccessGuard(repo);
    }

    private void assertNotFound(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("跨院区角色：直接放行，不做额外查询")
    void allCampusesSkipsLookup() {
        UUID id = UUID.randomUUID();

        assertThatCode(() -> guard.requireIssue(DataScope.all("admin", true), id))
                .doesNotThrowAnyException();
        verify(repo, never()).findIssueCampus(id);
    }

    @Test
    @DisplayName("出库单：本院区放行，跨院区与不存在同为 404")
    void issueCampusIsolation() {
        UUID own = UUID.randomUUID();
        when(repo.findIssueCampus(own)).thenReturn(Optional.of(campusA));
        assertThatCode(() -> guard.requireIssue(onlyA, own)).doesNotThrowAnyException();

        UUID other = UUID.randomUUID();
        when(repo.findIssueCampus(other)).thenReturn(Optional.of(campusB));
        assertNotFound(() -> guard.requireIssue(onlyA, other), ErrorCode.CONSUMABLE_ISSUE_NOT_FOUND);

        UUID missing = UUID.randomUUID();
        when(repo.findIssueCampus(missing)).thenReturn(Optional.empty());
        assertNotFound(() -> guard.requireIssue(onlyA, missing),
                ErrorCode.CONSUMABLE_ISSUE_NOT_FOUND);
    }

    @Test
    @DisplayName("出库明细：回溯出库单院区，越权按明细不存在处理")
    void issueItemCampusIsolation() {
        UUID itemId = UUID.randomUUID();
        when(repo.findIssueItemCampus(itemId)).thenReturn(Optional.of(campusB));

        assertNotFound(() -> guard.requireIssueItem(onlyA, itemId),
                ErrorCode.CONSUMABLE_ISSUE_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("退回单：越权按退回单不存在处理")
    void returnCampusIsolation() {
        UUID id = UUID.randomUUID();
        when(repo.findReturnCampus(id)).thenReturn(Optional.of(campusB));

        assertNotFound(() -> guard.requireReturn(onlyA, id),
                ErrorCode.CONSUMABLE_RETURN_NOT_FOUND);
    }

    @Test
    @DisplayName("调拨单：越权按调拨单不存在处理")
    void transferCampusIsolation() {
        UUID id = UUID.randomUUID();
        when(repo.findDeptTransferCampus(id)).thenReturn(Optional.of(campusB));

        assertNotFound(() -> guard.requireTransfer(onlyA, id),
                ErrorCode.DEPT_TRANSFER_NOT_FOUND);
    }
}
